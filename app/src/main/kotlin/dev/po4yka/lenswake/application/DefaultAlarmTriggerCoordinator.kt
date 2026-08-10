package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.alarm.AlarmHandlingResult
import dev.po4yka.lenswake.alarm.AlarmKind
import dev.po4yka.lenswake.alarm.AlarmTrigger
import dev.po4yka.lenswake.alarm.AlarmTriggerCoordinator
import dev.po4yka.lenswake.automation.AutomationEngine
import dev.po4yka.lenswake.automation.AutomationRunResult
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.EnvironmentSnapshotCaptureResult
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.EnvironmentSnapshotRepository
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Validates exact-alarm identity and bridges a persisted execution plan into the engine. */
class DefaultAlarmTriggerCoordinator(
    private val scheduleRepository: ScheduleRepository,
    private val executionRepository: ExecutionRepository,
    private val environmentSnapshotRepository: EnvironmentSnapshotRepository,
    private val environmentSnapshotCollector: EnvironmentSnapshotCollector,
    private val automationEngine: AutomationEngine,
    private val startReadiness: suspend (ProfileId) -> Result<Unit>,
    private val clock: LenswakeClock,
    private val snapshotCollectionTimeoutMillis: Long = SNAPSHOT_COLLECTION_TIMEOUT_MILLIS,
) : AlarmTriggerCoordinator {
    init {
        require(snapshotCollectionTimeoutMillis > 0) { "Snapshot collection timeout must be positive" }
    }

    override suspend fun handle(trigger: AlarmTrigger): AlarmHandlingResult {
        val schedule = try {
            scheduleRepository.get(trigger.scheduleId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return retryable("Could not load the alarm schedule", error)
        } ?: return terminal("The alarm schedule no longer exists")

        validateTrigger(trigger, schedule)?.let { return it }
        return when (trigger.kind) {
            AlarmKind.START -> handleStart(schedule)
            AlarmKind.STOP -> handleStop(schedule)
        }
    }

    private suspend fun handleStart(schedule: RecordingSchedule): AlarmHandlingResult {
        val executionKey = executionKey(schedule)
        val sessionId = deterministicSessionId(executionKey)
        val now = clock.now()
        val candidate = ExecutionSession(
            id = sessionId,
            executionKey = executionKey,
            kind = SessionKind.SCHEDULED,
            scheduleId = schedule.id,
            scheduleName = schedule.name,
            profileId = schedule.profileId,
            capture = schedule.capture,
            expectedStartAt = schedule.startAt,
            expectedStopAt = schedule.stopAt,
            alarmStartDeliveredAt = now,
            status = SessionStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        val reservation = try {
            executionRepository.reservePixelCamera(candidate)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return retryable("Could not reserve Pixel Camera for the execution", error)
        }
        val existing = when (reservation) {
            is ExecutionReservationResult.Reserved -> reservation.session
            is ExecutionReservationResult.CameraBusy -> return terminal(
                "Pixel Camera is owned by execution ${reservation.owner.id.value}",
            )
        }
        validateExecution(existing, schedule, executionKey)?.let { return it }
        if (existing.recordActionAt == null && existing.cameraOwnershipReleasedAt == null) {
            val readinessFailure = try {
                startReadiness(existing.profileId).exceptionOrNull()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                error
            }
            if (readinessFailure != null) {
                return failStartReadiness(existing, readinessFailure)
            }
        }
        val snapshottedSession = when (val snapshot = ensureEnvironmentSnapshot(existing)) {
            is SnapshotCheckpoint.Ready -> snapshot.session
            is SnapshotCheckpoint.Failed -> return snapshot.result
        }
        return runEngine(AlarmKind.START) { automationEngine.start(snapshottedSession.id) }
    }

    private suspend fun failStartReadiness(
        session: ExecutionSession,
        cause: Throwable,
    ): AlarmHandlingResult {
        if (session.revision == Long.MAX_VALUE) {
            return terminal("START readiness failed and the execution revision cannot advance")
        }
        val failedAt = maxOf(clock.now(), session.updatedAt)
        val failure = AutomationFailure(
            code = AutomationFailureCode.RUNTIME_READINESS_FAILED,
            message = "Scheduled START runtime readiness failed: " +
                (cause.message?.takeIf(String::isNotBlank) ?: cause.javaClass.simpleName),
        )
        val failed = session.copy(
            status = SessionStatus.FAILED,
            currentAutomationState = AutomationStateName.FAILED,
            cameraOwnershipReleasedAt = failedAt,
            failure = failure,
            revision = session.revision + 1,
            updatedAt = failedAt,
        )
        val event = AutomationEvent(
            id = EventId.new(),
            sessionId = session.id,
            name = "automation.start.readiness_failed",
            sequence = failed.revision,
            timestamp = failedAt,
            state = AutomationStateName.FAILED,
            outcome = AutomationOutcome.FAILED,
            failure = failure,
        )
        return try {
            when (executionRepository.apply(ExecutionChange(session.revision, failed), event)) {
                is ExecutionApplyResult.Applied -> terminal(failure.message)
                is ExecutionApplyResult.RevisionConflict -> retryable(
                    "START readiness failure lost a concurrent execution transition",
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            retryable("Could not persist START readiness failure", error)
        }
    }

    private suspend fun ensureEnvironmentSnapshot(session: ExecutionSession): SnapshotCheckpoint {
        val linkedSnapshotId = session.environmentSnapshotId
        if (linkedSnapshotId != null) {
            val existing = try {
                environmentSnapshotRepository.getEnvironmentSnapshot(linkedSnapshotId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return SnapshotCheckpoint.Failed(
                    retryable("Could not load the linked environment snapshot", error),
                )
            }
            return if (existing?.sessionId == session.id) {
                SnapshotCheckpoint.Ready(session)
            } else {
                SnapshotCheckpoint.Failed(terminal("Execution points to a missing environment snapshot"))
            }
        }

        val existing = try {
            environmentSnapshotRepository.getEnvironmentSnapshotForSession(session.id)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return SnapshotCheckpoint.Failed(
                retryable("Could not check for an existing environment snapshot", error),
            )
        }
        if (existing != null) {
            val refreshed = loadExecution(session.id)
            if (refreshed.isFailure) {
                return SnapshotCheckpoint.Failed(
                    retryable("Could not reload the snapshotted execution", refreshed.exceptionOrNull()),
                )
            }
            val linked = refreshed.getOrNull()
            return if (linked?.environmentSnapshotId == existing.id) {
                SnapshotCheckpoint.Ready(linked)
            } else {
                SnapshotCheckpoint.Failed(terminal("Environment snapshot is not linked from its execution"))
            }
        }

        val snapshotId = deterministicSnapshotId(session.id)
        val collected = try {
            withTimeout(snapshotCollectionTimeoutMillis) {
                environmentSnapshotCollector.collect(snapshotId, session.id)
            }
        } catch (_: TimeoutCancellationException) {
            return SnapshotCheckpoint.Failed(
                retryable("Environment snapshot collection exceeded its finite timeout"),
            )
        } catch (error: CancellationException) {
            throw error
        }
        if (collected.isFailure) {
            return SnapshotCheckpoint.Failed(
                retryable("Could not capture the execution environment", collected.exceptionOrNull()),
            )
        }
        val snapshot = checkNotNull(collected.getOrNull())
        if (snapshot.id != snapshotId || snapshot.sessionId != session.id) {
            return SnapshotCheckpoint.Failed(terminal("Environment collector returned a mismatched snapshot"))
        }
        val capture = try {
            environmentSnapshotRepository.capture(snapshot)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return SnapshotCheckpoint.Failed(
                retryable("Could not persist the execution environment", error),
            )
        }
        return when (capture) {
            is EnvironmentSnapshotCaptureResult.Captured -> validateCapturedSnapshot(
                expectedSnapshotId = snapshotId,
                snapshotSessionId = capture.snapshot.sessionId,
                capturedSnapshotId = capture.snapshot.id,
                session = capture.session,
            )
            is EnvironmentSnapshotCaptureResult.AlreadyExists -> validateCapturedSnapshot(
                expectedSnapshotId = capture.existing.id,
                snapshotSessionId = capture.existing.sessionId,
                capturedSnapshotId = capture.existing.id,
                session = capture.session,
            )
        }
    }

    private fun validateCapturedSnapshot(
        expectedSnapshotId: EnvironmentSnapshotId,
        snapshotSessionId: SessionId,
        capturedSnapshotId: EnvironmentSnapshotId,
        session: ExecutionSession,
    ): SnapshotCheckpoint = if (
        snapshotSessionId == session.id &&
        capturedSnapshotId == expectedSnapshotId &&
        session.environmentSnapshotId == capturedSnapshotId
    ) {
        SnapshotCheckpoint.Ready(session)
    } else {
        SnapshotCheckpoint.Failed(terminal("Persisted environment snapshot linkage is inconsistent"))
    }

    private suspend fun handleStop(schedule: RecordingSchedule): AlarmHandlingResult {
        val executionKey = executionKey(schedule)
        val deterministicId = deterministicSessionId(executionKey)
        val active = try {
            executionRepository.findActiveForSchedule(schedule.id)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return retryable("Could not locate the active execution session", error)
        }
        val deterministicLookup = if (active == null) loadExecution(deterministicId) else null
        if (deterministicLookup?.isFailure == true) {
            return retryable(
                "Could not load the execution for this STOP alarm",
                deterministicLookup.exceptionOrNull(),
            )
        }
        val session = active ?: deterministicLookup?.getOrNull()
            ?: return retryable("No persisted execution exists for this STOP alarm")
        validateExecution(session, schedule, executionKey)?.let { return it }
        val deliveredSession = when (val delivery = persistStopDelivery(session)) {
            is StopDeliveryResult.Persisted -> delivery.session
            is StopDeliveryResult.Failed -> return delivery.result
        }
        return when (val reconciliation = reconcileStopDelivery(deliveredSession)) {
            StopReconciliation.NoCameraWork -> AlarmHandlingResult.Accepted
            is StopReconciliation.StopRequired -> runEngine(AlarmKind.STOP) {
                automationEngine.stop(reconciliation.session.id)
            }
            is StopReconciliation.Failed -> reconciliation.result
        }
    }

    private suspend fun reconcileStopDelivery(session: ExecutionSession): StopReconciliation {
        if (session.stoppedVerifiedAt != null) return StopReconciliation.NoCameraWork
        if (session.cameraOwnershipReleasedAt != null) return StopReconciliation.NoCameraWork
        if (session.recordActionAt != null) return StopReconciliation.StopRequired(session)
        if (session.status in TERMINAL_STATUSES) return StopReconciliation.NoCameraWork
        if (session.status !in setOf(SessionStatus.PENDING, SessionStatus.STARTING)) {
            return StopReconciliation.Failed(
                terminal("Persisted recording state has no Pixel Camera ownership checkpoint"),
            )
        }
        if (session.revision == Long.MAX_VALUE) {
            return StopReconciliation.Failed(
                terminal("STOP reconciliation cannot increment the execution revision"),
            )
        }

        val reconciledAt = maxOf(clock.now(), session.updatedAt)
        val failure = AutomationFailure(
            AutomationFailureCode.AUTOMATION_CANCELLED,
            "Scheduled STOP became due before recording ownership was acquired",
        )
        val cancelled = session.copy(
            status = SessionStatus.CANCELLED,
            currentAutomationState = AutomationStateName.CANCELLED,
            failure = failure,
            revision = session.revision + 1,
            updatedAt = reconciledAt,
        )
        val event = AutomationEvent(
            id = EventId.new(),
            sessionId = session.id,
            name = STOP_RECONCILED_WITHOUT_OWNERSHIP_EVENT,
            sequence = cancelled.revision,
            timestamp = reconciledAt,
            state = AutomationStateName.CANCELLED,
            outcome = AutomationOutcome.CANCELLED,
            failure = failure,
            metadata = mapOf("alarmKind" to AlarmKind.STOP.name),
        )
        return try {
            when (
                executionRepository.apply(
                    ExecutionChange(session.revision, cancelled),
                    event,
                )
            ) {
                is ExecutionApplyResult.Applied -> StopReconciliation.NoCameraWork
                is ExecutionApplyResult.RevisionConflict -> {
                    val current = executionRepository.get(session.id)
                        ?: return StopReconciliation.Failed(
                            retryable("Execution disappeared during STOP reconciliation"),
                        )
                    when {
                        current.stoppedVerifiedAt != null -> StopReconciliation.NoCameraWork
                        current.recordActionAt != null -> StopReconciliation.StopRequired(current)
                        current.status in TERMINAL_STATUSES -> StopReconciliation.NoCameraWork
                        else -> StopReconciliation.Failed(
                            retryable("STOP reconciliation lost a concurrent state transition"),
                        )
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            StopReconciliation.Failed(
                retryable("Could not persist STOP reconciliation", error),
            )
        }
    }

    private suspend fun persistStopDelivery(session: ExecutionSession): StopDeliveryResult {
        if (session.alarmStopDeliveredAt != null) return StopDeliveryResult.Persisted(session)
        if (session.revision == Long.MAX_VALUE) {
            return StopDeliveryResult.Failed(terminal("STOP delivery cannot increment the execution revision"))
        }

        val deliveredAt = maxOf(clock.now(), session.updatedAt)
        val updated = session.copy(
            alarmStopDeliveredAt = deliveredAt,
            revision = session.revision + 1,
            updatedAt = deliveredAt,
        )
        val event = AutomationEvent(
            id = EventId.new(),
            sessionId = session.id,
            name = STOP_DELIVERED_EVENT,
            sequence = updated.revision,
            timestamp = deliveredAt,
            state = AutomationStateName.STOP_TRIGGERED,
            outcome = AutomationOutcome.SUCCEEDED,
            metadata = mapOf("alarmKind" to AlarmKind.STOP.name),
        )
        val result = try {
            executionRepository.apply(
                change = ExecutionChange(session.revision, updated),
                event = event,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return StopDeliveryResult.Failed(
                retryable("Could not persist STOP alarm delivery", error),
            )
        }
        return when (result) {
            is ExecutionApplyResult.Applied -> StopDeliveryResult.Persisted(result.session)
            is ExecutionApplyResult.RevisionConflict -> {
                val winner = loadExecution(session.id)
                if (winner.isFailure) {
                    StopDeliveryResult.Failed(
                        retryable(
                            "Could not resolve concurrent STOP delivery",
                            winner.exceptionOrNull(),
                        ),
                    )
                } else {
                    val current = winner.getOrNull()
                    if (current?.alarmStopDeliveredAt != null) {
                        StopDeliveryResult.Persisted(current)
                    } else {
                        StopDeliveryResult.Failed(
                            retryable("STOP delivery lost a concurrent state transition"),
                        )
                    }
                }
            }
        }
    }

    private fun validateTrigger(
        trigger: AlarmTrigger,
        schedule: RecordingSchedule,
    ): AlarmHandlingResult.TerminalRejected? {
        if (!schedule.enabled) return terminal("The alarm schedule is disabled")
        if (schedule.updatedAt != trigger.scheduleUpdatedAt) {
            return terminal("The alarm belongs to an obsolete schedule revision")
        }
        val expected = when (trigger.kind) {
            AlarmKind.START -> schedule.startAt
            AlarmKind.STOP -> schedule.stopAt
        }
        if (expected != trigger.expectedAt) {
            return terminal("The alarm expected time does not match the persisted schedule")
        }
        val now = clock.now()
        if (now.isBefore(expected)) return terminal("The alarm was delivered before its expected time")
        if (trigger.kind == AlarmKind.START && !now.isBefore(schedule.stopAt)) {
            return terminal("The START alarm arrived after the scheduled stop time")
        }
        return null
    }

    private fun validateExecution(
        session: ExecutionSession,
        schedule: RecordingSchedule,
        executionKey: String,
    ): AlarmHandlingResult.TerminalRejected? {
        if (
            session.executionKey != executionKey ||
            session.scheduleId != schedule.id ||
            session.profileId != schedule.profileId ||
            session.expectedStartAt != schedule.startAt ||
            session.expectedStopAt != schedule.stopAt ||
            session.capture != schedule.capture
        ) {
            return terminal("The persisted execution does not match the current schedule intent")
        }
        return null
    }

    private suspend fun loadExecution(id: SessionId): Result<ExecutionSession?> = try {
        Result.success(executionRepository.get(id))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    }

    private fun mapEngineResult(
        result: AutomationRunResult,
        kind: AlarmKind,
    ): AlarmHandlingResult = when (result) {
        is AutomationRunResult.Succeeded,
        is AutomationRunResult.AlreadySatisfied,
        is AutomationRunResult.StopVerifiedAfterFailure,
        -> AlarmHandlingResult.Accepted

        is AutomationRunResult.AlreadyTerminal -> AlarmHandlingResult.Accepted
        is AutomationRunResult.NotFound -> retryable("The persisted execution disappeared before automation")
        is AutomationRunResult.Rejected -> terminal(
            "$kind automation was rejected: ${result.failure.message}",
        )
        is AutomationRunResult.Failed -> terminal(
            "$kind automation failed: ${result.failure.message}",
        )
        is AutomationRunResult.StartReconciliationRequired -> retryable(
            "$kind automation requires recording-state reconciliation: ${result.failure.message}",
        )
        is AutomationRunResult.RevisionConflict -> retryable(
            "$kind automation lost a concurrent state transition",
        )
        is AutomationRunResult.PersistenceFailure -> retryable(
            "$kind automation could not persist its state: ${result.failure.message}",
        )
    }

    private suspend fun runEngine(
        kind: AlarmKind,
        run: suspend () -> AutomationRunResult,
    ): AlarmHandlingResult = try {
        mapEngineResult(run(), kind)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        retryable("$kind automation terminated unexpectedly", error)
    }

    private fun executionKey(schedule: RecordingSchedule): String =
        "schedule/${schedule.id.value}/${schedule.startAt.toEpochMilli()}"

    private fun deterministicSessionId(executionKey: String): SessionId = SessionId(
        UUID.nameUUIDFromBytes(executionKey.toByteArray(StandardCharsets.UTF_8)).toString(),
    )

    private fun deterministicSnapshotId(sessionId: SessionId): EnvironmentSnapshotId = EnvironmentSnapshotId(
        UUID.nameUUIDFromBytes("environment/${sessionId.value}".toByteArray(StandardCharsets.UTF_8)).toString(),
    )

    private fun terminal(reason: String): AlarmHandlingResult.TerminalRejected =
        AlarmHandlingResult.TerminalRejected(reason)

    private fun retryable(
        reason: String,
        cause: Throwable? = null,
    ): AlarmHandlingResult.Retryable = AlarmHandlingResult.Retryable(reason, cause)

    private sealed interface StopDeliveryResult {
        data class Persisted(val session: ExecutionSession) : StopDeliveryResult
        data class Failed(val result: AlarmHandlingResult) : StopDeliveryResult
    }

    private sealed interface StopReconciliation {
        data object NoCameraWork : StopReconciliation
        data class StopRequired(val session: ExecutionSession) : StopReconciliation
        data class Failed(val result: AlarmHandlingResult) : StopReconciliation
    }

    private sealed interface SnapshotCheckpoint {
        data class Ready(val session: ExecutionSession) : SnapshotCheckpoint
        data class Failed(val result: AlarmHandlingResult) : SnapshotCheckpoint
    }

    private companion object {
        const val STOP_DELIVERED_EVENT = "automation.alarm.stop_delivered"
        const val STOP_RECONCILED_WITHOUT_OWNERSHIP_EVENT =
            "automation.alarm.stop_reconciled_without_ownership"
        const val SNAPSHOT_COLLECTION_TIMEOUT_MILLIS = 5_000L
        val TERMINAL_STATUSES = setOf(
            SessionStatus.COMPLETED,
            SessionStatus.FAILED,
            SessionStatus.CANCELLED,
        )
    }
}
