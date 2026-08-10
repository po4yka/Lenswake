package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.alarm.AlarmHandlingResult
import dev.po4yka.lenswake.alarm.RehearsalStopBackstop
import dev.po4yka.lenswake.alarm.RehearsalStopTrigger
import dev.po4yka.lenswake.alarm.RehearsalStopTriggerCoordinator
import dev.po4yka.lenswake.automation.AutomationEngine
import dev.po4yka.lenswake.automation.AutomationRunResult
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.EnvironmentSnapshotCaptureResult
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.EnvironmentSnapshotRepository
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionReport
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.UiSelectorSet
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

fun interface RehearsalDelay {
    suspend fun wait(duration: Duration)
}

object MonotonicRehearsalDelay : RehearsalDelay {
    override suspend fun wait(duration: Duration) {
        delay(duration.toMillis())
    }
}

class DefaultRehearsalCoordinator(
    private val profileRepository: AutomationProfileRepository,
    private val executionRepository: ExecutionRepository,
    private val environmentSnapshotRepository: EnvironmentSnapshotRepository,
    private val environmentSnapshotCollector: EnvironmentSnapshotCollector,
    private val environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    private val automationEngine: AutomationEngine,
    private val backstop: RehearsalStopBackstop,
    private val stopWorkflow: RehearsalStopWorkflow,
    private val clock: LenswakeClock,
    private val mutex: Mutex,
    private val rehearsalDelay: RehearsalDelay = MonotonicRehearsalDelay,
) : RehearsalCoordinator {
    override suspend fun run(request: RehearsalRequest): RehearsalResult {
        val preparation = mutex.withLock { prepareAndStart(request) }
        val started = when (preparation) {
            is StartPreparation.Started -> preparation
            is StartPreparation.Result -> return preparation.value
        }
        if (!started.result.hasVerifiedRecordingOwnership()) {
            return handleFailedStart(started.sessionId, started.result)
        }

        try {
            rehearsalDelay.wait(request.recordingDuration)
        } catch (cancelled: CancellationException) {
            boundedSafetyStop(started.sessionId)
            throw cancelled
        } catch (failure: Exception) {
            return when (val cleanup = boundedSafetyStop(started.sessionId)) {
                is RehearsalStopOutcome.Promoted -> RehearsalResult.Completed(cleanup.session, cleanup.profile)
                is RehearsalStopOutcome.SafeFailure -> rejected(
                    RehearsalResultCode.STOP_FAILED,
                    failure.message ?: "Rehearsal delay failed",
                    started.sessionId,
                )
                is RehearsalStopOutcome.Invalid,
                is RehearsalStopOutcome.Retryable,
                -> RehearsalResult.SafetyStopPending(
                    started.sessionId,
                    "Rehearsal delay failed and STOP remains unverified; backstop retained",
                )
            }
        }
        return when (val stopped = stopWorkflow.stopInline(started.sessionId)) {
            is RehearsalStopOutcome.Promoted -> RehearsalResult.Completed(
                stopped.session,
                stopped.profile,
            )
            is RehearsalStopOutcome.SafeFailure -> RehearsalResult.Rejected(
                code = RehearsalResultCode.STOP_FAILED,
                message = stopped.message,
                sessionId = started.sessionId,
            )
            is RehearsalStopOutcome.Retryable -> RehearsalResult.SafetyStopPending(
                sessionId = started.sessionId,
                message = stopped.message,
            )
            is RehearsalStopOutcome.Invalid -> RehearsalResult.Rejected(
                code = RehearsalResultCode.INVALID_STOP_TRIGGER,
                message = stopped.message,
                sessionId = started.sessionId,
            )
        }
    }

    private suspend fun prepareAndStart(request: RehearsalRequest): StartPreparation {
        val profile = persistence("load profile") { profileRepository.get(request.profileId) }
            .getOrElse { failure ->
                return StartPreparation.Result(
                    rejected(RehearsalResultCode.SESSION_PERSISTENCE_FAILED, failure.message ?: "Read failed"),
                )
            }
            ?: return StartPreparation.Result(
                rejected(RehearsalResultCode.PROFILE_NOT_FOUND, "Pixel Camera profile was not found"),
            )
        val currentEnvironment = when (val observed = inspectEnvironment()) {
            is PortResult.Observed -> observed.value
            is PortResult.Unavailable -> return StartPreparation.Result(
                rejected(RehearsalResultCode.ENVIRONMENT_UNAVAILABLE, observed.failure.message),
            )
        }
        if (profile.environment != currentEnvironment) {
            return StartPreparation.Result(
                rejected(
                    RehearsalResultCode.ENVIRONMENT_MISMATCH,
                    "The profile does not exactly match the current Pixel Camera environment",
                ),
            )
        }

        val createdAt = clock.now()
        val sessionId = SessionId.new()
        val session = ExecutionSession(
            id = sessionId,
            executionKey = "rehearsal/${sessionId.value}/${profile.definitionFingerprint()}",
            kind = SessionKind.REHEARSAL,
            scheduleId = null,
            scheduleName = "Rehearsal",
            profileId = profile.id,
            capture = request.capture,
            expectedStartAt = createdAt,
            expectedStopAt = createdAt
                .plus(START_BUDGET)
                .plus(request.recordingDuration)
                .plus(STOP_MARGIN),
            status = SessionStatus.PENDING,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        val reservation = persistence("reserve Pixel Camera for rehearsal") {
            executionRepository.reservePixelCamera(session)
        }.getOrElse { failure ->
            return StartPreparation.Result(
                rejected(
                    RehearsalResultCode.SESSION_PERSISTENCE_FAILED,
                    failure.message ?: "Session create failed",
                    sessionId,
                ),
            )
        }
        val created = when (reservation) {
            is ExecutionReservationResult.Reserved -> reservation.session
            is ExecutionReservationResult.CameraBusy -> return StartPreparation.Result(
                RehearsalResult.Busy(reservation.owner.id),
            )
        }

        val snapshotResult = try {
            captureSnapshot(created, currentEnvironment)
        } catch (cancelled: CancellationException) {
            handleCancelledPreparation(sessionId)
            throw cancelled
        }
        val snapshotted = snapshotResult.getOrElse { failure ->
            failBeforeDispatch(sessionId, failure.message ?: "Snapshot capture failed")
            return StartPreparation.Result(
                rejected(
                    RehearsalResultCode.SNAPSHOT_CAPTURE_FAILED,
                    failure.message ?: "Snapshot capture failed",
                    sessionId,
                ),
            )
        }
        val armed = try {
            backstop.schedule(snapshotted.id)
        } catch (cancelled: CancellationException) {
            handleCancelledPreparation(sessionId)
            throw cancelled
        }
        if (armed.isFailure) {
            failBeforeDispatch(sessionId, "Rehearsal STOP backstop could not be armed")
            boundedCancelBackstop(sessionId)
            return StartPreparation.Result(
                rejected(
                    RehearsalResultCode.BACKSTOP_UNAVAILABLE,
                    "An exact independent STOP backstop is required before rehearsal START",
                    sessionId,
                ),
            )
        }

        val startResult = try {
            withTimeout(START_BUDGET.toMillis()) {
                automationEngine.start(sessionId)
            }
        } catch (_: TimeoutCancellationException) {
            AutomationRunResult.PersistenceFailure(
                session = executionRepository.get(sessionId),
                failure = AutomationFailure(
                    AutomationFailureCode.AUTOMATION_TIMEOUT,
                    "Rehearsal START exceeded its finite safety budget",
                ),
            )
        } catch (cancelled: CancellationException) {
            handleCancelledPreparation(sessionId)
            throw cancelled
        } catch (failure: Exception) {
            AutomationRunResult.PersistenceFailure(
                session = executionRepository.get(sessionId),
                failure = AutomationFailure(
                    AutomationFailureCode.UNKNOWN,
                    "Rehearsal START terminated unexpectedly",
                ),
            )
        }
        return StartPreparation.Started(sessionId, startResult)
    }

    private suspend fun handleFailedStart(
        sessionId: SessionId,
        result: AutomationRunResult,
    ): RehearsalResult {
        val current = executionRepository.get(sessionId)
        val hasOwnership = current?.recordActionAt != null && current.stoppedVerifiedAt == null
        if (!hasOwnership) {
            failBeforeDispatch(sessionId, result.failureMessage())
            boundedCancelBackstop(sessionId)
            return rejected(
                RehearsalResultCode.START_FAILED,
                result.failureMessage(),
                sessionId,
            )
        }

        val cleanup = boundedSafetyStop(sessionId)
        return when (cleanup) {
            is RehearsalStopOutcome.SafeFailure -> rejected(
                RehearsalResultCode.START_FAILED,
                result.failureMessage(),
                sessionId,
            )
            is RehearsalStopOutcome.Promoted -> RehearsalResult.Completed(cleanup.session, cleanup.profile)
            is RehearsalStopOutcome.Invalid,
            is RehearsalStopOutcome.Retryable,
            -> RehearsalResult.SafetyStopPending(
                sessionId,
                "START was not successful and recording ownership remains possible; STOP backstop retained",
            )
        }
    }

    private suspend fun boundedSafetyStop(sessionId: SessionId): RehearsalStopOutcome =
        withContext(NonCancellable) {
            try {
                withTimeout(CLEANUP_TIMEOUT.toMillis()) {
                    stopWorkflow.stopSafety(sessionId)
                }
            } catch (_: TimeoutCancellationException) {
                RehearsalStopOutcome.Retryable("Safety STOP cleanup exceeded its finite timeout")
            } catch (failure: Exception) {
                RehearsalStopOutcome.Retryable(
                    failure.message ?: "Safety STOP cleanup failed",
                )
            }
        }

    private suspend fun handleCancelledPreparation(sessionId: SessionId) {
        withContext(NonCancellable) {
            val current = runCatching { executionRepository.get(sessionId) }.getOrNull() ?: return@withContext
            if (current.recordActionAt != null && current.stoppedVerifiedAt == null) {
                boundedSafetyStop(sessionId)
            } else {
                failBeforeDispatch(sessionId, "Rehearsal preparation was cancelled")
                boundedCancelBackstop(sessionId)
            }
        }
    }

    private suspend fun captureSnapshot(
        session: ExecutionSession,
        expectedEnvironment: PixelCameraEnvironment,
    ): Result<ExecutionSession> = persistence("capture environment snapshot") {
        val snapshotId = deterministicSnapshotId(session.id)
        val snapshot = withTimeout(SNAPSHOT_TIMEOUT.toMillis()) {
            environmentSnapshotCollector.collect(snapshotId, session.id).getOrThrow()
        }
        check(snapshot.id == snapshotId && snapshot.sessionId == session.id) {
            "Environment collector returned a mismatched snapshot"
        }
        check(snapshot.cameraEnvironment == expectedEnvironment) {
            "Pixel Camera environment changed during rehearsal preparation"
        }
        when (val capture = environmentSnapshotRepository.capture(snapshot)) {
            is EnvironmentSnapshotCaptureResult.Captured -> capture.session
            is EnvironmentSnapshotCaptureResult.AlreadyExists -> capture.session
        }.also { linked ->
            check(linked.environmentSnapshotId == snapshot.id) {
                "Environment snapshot was not linked to the rehearsal"
            }
        }
    }

    private suspend fun failBeforeDispatch(sessionId: SessionId, message: String) {
        val session = executionRepository.get(sessionId) ?: return
        if (session.recordActionAt != null || session.status in TERMINAL_STATUSES) return
        persistTransition(
            session = session,
            updated = session.copy(
                status = SessionStatus.FAILED,
                currentAutomationState = AutomationStateName.FAILED,
                failure = AutomationFailure(AutomationFailureCode.UNKNOWN, message.ifBlank { "Rehearsal failed" }),
            ),
            eventName = "automation.rehearsal.failed_before_dispatch",
            outcome = AutomationOutcome.FAILED,
        )
    }

    private suspend fun boundedCancelBackstop(sessionId: SessionId) {
        withContext(NonCancellable) {
            runCatching {
                withTimeout(BACKSTOP_CANCEL_TIMEOUT.toMillis()) {
                    backstop.cancel(sessionId).getOrThrow()
                }
            }
        }
    }

    private fun inspectEnvironment(): PortResult<PixelCameraEnvironment> = try {
        environmentProbe()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        PortResult.Unavailable(
            AutomationFailure(
                AutomationFailureCode.UNKNOWN,
                "Pixel Camera environment inspection failed",
            ),
        )
    }

    private suspend fun persistTransition(
        session: ExecutionSession,
        updated: ExecutionSession,
        eventName: String,
        outcome: AutomationOutcome,
    ): ExecutionSession? {
        if (session.revision == Long.MAX_VALUE) return null
        val now = maxOf(clock.now(), session.updatedAt)
        val next = updated.copy(revision = session.revision + 1, updatedAt = now)
        val event = AutomationEvent(
            id = EventId.new(),
            sessionId = session.id,
            name = eventName,
            sequence = next.revision,
            timestamp = now,
            state = next.currentAutomationState ?: AutomationStateName.FAILED,
            outcome = outcome,
            failure = next.failure,
        )
        return when (val applied = executionRepository.apply(ExecutionChange(session.revision, next), event)) {
            is ExecutionApplyResult.Applied -> applied.session
            is ExecutionApplyResult.RevisionConflict -> null
        }
    }

    private fun deterministicSnapshotId(sessionId: SessionId): EnvironmentSnapshotId = EnvironmentSnapshotId(
        UUID.nameUUIDFromBytes("environment/${sessionId.value}".toByteArray(StandardCharsets.UTF_8)).toString(),
    )

    private suspend inline fun <T> persistence(
        stage: String,
        crossinline block: suspend () -> T,
    ): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(IllegalStateException("$stage failed", failure))
    }

    private fun rejected(
        code: RehearsalResultCode,
        message: String,
        sessionId: SessionId? = null,
    ) = RehearsalResult.Rejected(code, message, sessionId)

    private sealed interface StartPreparation {
        data class Started(val sessionId: SessionId, val result: AutomationRunResult) : StartPreparation
        data class Result(val value: RehearsalResult) : StartPreparation
    }

    private companion object {
        val START_BUDGET: Duration = Duration.ofSeconds(60)
        val STOP_MARGIN: Duration = Duration.ofSeconds(30)
        val SNAPSHOT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val CLEANUP_TIMEOUT: Duration = Duration.ofSeconds(20)
        val BACKSTOP_CANCEL_TIMEOUT: Duration = Duration.ofSeconds(5)
        val TERMINAL_STATUSES = setOf(SessionStatus.COMPLETED, SessionStatus.FAILED, SessionStatus.CANCELLED)
    }
}

class DefaultRehearsalStopTriggerCoordinator(
    private val stopWorkflow: RehearsalStopWorkflow,
) : RehearsalStopTriggerCoordinator {
    override suspend fun handle(trigger: RehearsalStopTrigger): AlarmHandlingResult =
        when (val result = stopWorkflow.stopAlarm(trigger)) {
            is RehearsalStopOutcome.Promoted,
            is RehearsalStopOutcome.SafeFailure,
            -> AlarmHandlingResult.Accepted
            is RehearsalStopOutcome.Invalid -> AlarmHandlingResult.TerminalRejected(result.message)
            is RehearsalStopOutcome.Retryable -> AlarmHandlingResult.Retryable(result.message)
        }
}

class RehearsalStopWorkflow(
    private val executionRepository: ExecutionRepository,
    private val environmentSnapshotRepository: EnvironmentSnapshotRepository,
    private val profileRepository: AutomationProfileRepository,
    private val environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    private val automationEngine: AutomationEngine,
    private val backstop: RehearsalStopBackstop,
    private val clock: LenswakeClock,
    private val mutex: Mutex,
) {
    suspend fun stopInline(sessionId: SessionId): RehearsalStopOutcome = mutex.withLock {
        stopLocked(sessionId, alarmTrigger = null, promotionAllowed = true)
    }

    suspend fun stopSafety(sessionId: SessionId): RehearsalStopOutcome = mutex.withLock {
        stopLocked(sessionId, alarmTrigger = null, promotionAllowed = false)
    }

    suspend fun stopAlarm(trigger: RehearsalStopTrigger): RehearsalStopOutcome = mutex.withLock {
        stopLocked(trigger.sessionId, alarmTrigger = trigger, promotionAllowed = true)
    }

    private suspend fun stopLocked(
        sessionId: SessionId,
        alarmTrigger: RehearsalStopTrigger?,
        promotionAllowed: Boolean,
    ): RehearsalStopOutcome {
        var session = try {
            executionRepository.get(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RehearsalStopOutcome.Retryable("Could not load rehearsal session")
        } ?: return RehearsalStopOutcome.Invalid("Rehearsal session was not found")
        if (session.kind != SessionKind.REHEARSAL) {
            return RehearsalStopOutcome.Invalid("STOP target is not a rehearsal session")
        }
        if (alarmTrigger != null) {
            if (alarmTrigger.expectedAt != session.expectedStopAt) {
                return RehearsalStopOutcome.Invalid("Rehearsal STOP expected time is stale")
            }
            if (clock.now().isBefore(alarmTrigger.expectedAt)) {
                return RehearsalStopOutcome.Invalid("Rehearsal STOP alarm arrived before its expected time")
            }
            session = persistAlarmDelivery(session)
                ?: return RehearsalStopOutcome.Retryable("Could not persist rehearsal STOP delivery")
        }

        if (session.status == SessionStatus.COMPLETED) {
            return if (promotionAllowed) finalizeCompleted(session.id) else cancelSafelyStopped(session)
        }
        if (session.status in setOf(SessionStatus.FAILED, SessionStatus.CANCELLED) && session.stoppedVerifiedAt != null) {
            return cancelSafelyStopped(session)
        }
        if (session.stoppedVerifiedAt != null) return cancelSafelyStopped(session)
        if (session.cameraOwnershipReleasedAt != null) return cancelWithoutOwnership(session)
        if (session.recordActionAt == null) {
            return cancelWithoutOwnership(session)
        }

        val result = try {
            automationEngine.stop(session.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RehearsalStopOutcome.Retryable(
                failure.message ?: "Rehearsal STOP terminated unexpectedly",
            )
        }
        val currentSessionId = result.sessionOrNull()?.id ?: session.id
        val current = try {
            executionRepository.get(currentSessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RehearsalStopOutcome.Retryable("Could not reload rehearsal after STOP")
        } ?: return RehearsalStopOutcome.Retryable("Rehearsal disappeared after STOP")
        return when {
            current.status == SessionStatus.COMPLETED && promotionAllowed -> finalizeCompleted(current.id)
            current.stoppedVerifiedAt != null -> cancelSafelyStopped(current)
            else -> RehearsalStopOutcome.Retryable(result.failureMessage())
        }
    }

    private suspend fun persistAlarmDelivery(session: ExecutionSession): ExecutionSession? {
        if (session.alarmStopDeliveredAt != null) return session
        if (session.revision == Long.MAX_VALUE) return null
        val now = maxOf(clock.now(), session.updatedAt)
        val updated = session.copy(
            alarmStopDeliveredAt = now,
            revision = session.revision + 1,
            updatedAt = now,
        )
        val event = AutomationEvent(
            id = EventId.new(),
            sessionId = session.id,
            name = "automation.rehearsal.stop_alarm_delivered",
            sequence = updated.revision,
            timestamp = now,
            state = AutomationStateName.STOP_TRIGGERED,
            outcome = AutomationOutcome.SUCCEEDED,
        )
        return when (executionRepository.apply(ExecutionChange(session.revision, updated), event)) {
            is ExecutionApplyResult.Applied -> updated
            is ExecutionApplyResult.RevisionConflict -> executionRepository.get(session.id)?.takeIf {
                it.alarmStopDeliveredAt != null
            }
        }
    }

    private suspend fun finalizeCompleted(sessionId: SessionId): RehearsalStopOutcome {
        val report = try {
            environmentSnapshotRepository.report(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RehearsalStopOutcome.Retryable("Could not load rehearsal proof")
        } ?: return RehearsalStopOutcome.Retryable("Rehearsal report is missing")
        val proofFailure = validatePromotionProof(report)
        if (proofFailure != null) {
            return cancelSafelyStopped(
                report.session,
                "Rehearsal stopped safely but did not qualify for profile promotion: $proofFailure",
            )
        }

        val profile = try {
            profileRepository.get(report.session.profileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RehearsalStopOutcome.Retryable("Could not load rehearsal profile")
        } ?: return RehearsalStopOutcome.Retryable("Rehearsal profile is missing")
        if (report.session.testedProfileFingerprint() != profile.definitionFingerprint()) {
            return cancelSafelyStopped(
                report.session,
                "Rehearsal stopped safely, but the profile definition changed before promotion",
            )
        }
        val snapshot = checkNotNull(report.environmentSnapshot)
        val currentEnvironment = when (val observed = inspectEnvironment()) {
            is PortResult.Observed -> observed.value
            is PortResult.Unavailable -> return RehearsalStopOutcome.Retryable(observed.failure.message)
        }
        if (currentEnvironment != snapshot.cameraEnvironment || currentEnvironment != profile.environment) {
            return RehearsalStopOutcome.Retryable("Pixel Camera environment changed before profile promotion")
        }

        val verified = profile.copy(
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = checkNotNull(report.session.stoppedVerifiedAt),
        )
        try {
            profileRepository.save(verified)
            if (profileRepository.get(verified.id) != verified) {
                return RehearsalStopOutcome.Retryable("Verified profile read-back did not match")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return RehearsalStopOutcome.Retryable("Could not persist verified profile")
        }
        if (backstop.cancel(sessionId).isFailure) {
            return RehearsalStopOutcome.Retryable("Verified profile persisted but STOP backstop cancellation failed")
        }
        return RehearsalStopOutcome.Promoted(report.session, verified)
    }

    private fun validatePromotionProof(report: ExecutionReport): String? {
        val session = report.session
        val snapshot = report.environmentSnapshot
        return when {
            session.kind != SessionKind.REHEARSAL -> "Execution is not a rehearsal"
            session.status != SessionStatus.COMPLETED -> "Rehearsal is not completed"
            session.failure != null -> "Rehearsal completed with a failure"
            snapshot == null -> "Rehearsal environment snapshot is missing"
            snapshot.sessionId != session.id || snapshot.id != session.environmentSnapshotId ->
                "Rehearsal environment snapshot linkage is invalid"
            session.recordActionAt == null -> "Rehearsal record dispatch proof is missing"
            session.recordingVerifiedAt == null -> "Rehearsal recording verification is missing"
            session.stopActionAt == null -> "Rehearsal stop dispatch proof is missing"
            session.stoppedVerifiedAt == null -> "Rehearsal stop verification is missing"
            else -> null
        }
    }

    private suspend fun cancelSafelyStopped(
        session: ExecutionSession,
        message: String = "Rehearsal did not qualify for promotion but STOP was verified",
    ): RehearsalStopOutcome {
        return if (backstop.cancel(session.id).isSuccess) {
            RehearsalStopOutcome.SafeFailure(session, message)
        } else {
            RehearsalStopOutcome.Retryable("STOP was verified but backstop cancellation failed")
        }
    }

    private suspend fun cancelWithoutOwnership(session: ExecutionSession): RehearsalStopOutcome {
        return if (backstop.cancel(session.id).isSuccess) {
            RehearsalStopOutcome.SafeFailure(
                session,
                "Rehearsal never acquired recording ownership; no Camera STOP was dispatched",
            )
        } else {
            RehearsalStopOutcome.Retryable("No recording ownership exists, but backstop cancellation failed")
        }
    }

    private fun inspectEnvironment(): PortResult<PixelCameraEnvironment> = try {
        environmentProbe()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PortResult.Unavailable(
            AutomationFailure(AutomationFailureCode.UNKNOWN, "Pixel Camera environment inspection failed"),
        )
    }
}

sealed interface RehearsalStopOutcome {
    data class Promoted(val session: ExecutionSession, val profile: PixelCameraProfile) : RehearsalStopOutcome
    data class SafeFailure(val session: ExecutionSession, val message: String) : RehearsalStopOutcome
    data class Retryable(val message: String) : RehearsalStopOutcome
    data class Invalid(val message: String) : RehearsalStopOutcome
}

private fun AutomationRunResult.sessionOrNull(): ExecutionSession? = when (this) {
    is AutomationRunResult.Succeeded -> session
    is AutomationRunResult.AlreadySatisfied -> session
    is AutomationRunResult.AlreadyTerminal -> session
    is AutomationRunResult.StopVerifiedAfterFailure -> session
    is AutomationRunResult.Rejected -> session
    is AutomationRunResult.Failed -> session
    is AutomationRunResult.StartReconciliationRequired -> session
    is AutomationRunResult.RevisionConflict -> session
    is AutomationRunResult.PersistenceFailure -> session
    is AutomationRunResult.NotFound -> null
}

private fun AutomationRunResult.hasVerifiedRecordingOwnership(): Boolean {
    val session = sessionOrNull() ?: return false
    return this is AutomationRunResult.Succeeded &&
        session.status == SessionStatus.RECORDING &&
        session.recordActionAt != null &&
        session.recordingVerifiedAt != null
}

private fun AutomationRunResult.failureMessage(): String = when (this) {
    is AutomationRunResult.Rejected -> failure.message
    is AutomationRunResult.Failed -> failure.message
    is AutomationRunResult.PersistenceFailure -> failure.message
    is AutomationRunResult.StartReconciliationRequired -> failure.message
    is AutomationRunResult.RevisionConflict -> "Rehearsal lost a concurrent session transition"
    is AutomationRunResult.NotFound -> "Rehearsal session was not found"
    is AutomationRunResult.AlreadyTerminal -> "Rehearsal was already terminal"
    is AutomationRunResult.AlreadySatisfied -> "Rehearsal START was not newly verified"
    is AutomationRunResult.StopVerifiedAfterFailure -> "Rehearsal START failed and required safety STOP"
    is AutomationRunResult.Succeeded -> "Rehearsal result did not contain required ownership proof"
}

private fun ExecutionSession.testedProfileFingerprint(): String? = executionKey
    .substringAfterLast('/', missingDelimiterValue = "")
    .takeIf { it.length == SHA_256_HEX_LENGTH && it.all(Char::isHexDigit) }

private fun PixelCameraProfile.definitionFingerprint(): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        fun writeString(value: String?) {
            if (value == null) {
                output.writeInt(-1)
            } else {
                val encoded = value.toByteArray(StandardCharsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }

        fun writeSelectorSet(set: UiSelectorSet) {
            output.writeInt(set.minimumScore)
            output.writeInt(set.selectors.size)
            set.selectors.forEach { selector ->
                writeString(selector.packageName)
                writeString(selector.resourceId)
                writeString(selector.role)
                writeString(selector.contentDescription)
                writeString(selector.text)
                writeString(selector.expectedSelected?.toString())
                writeString(selector.expectedChecked?.toString())
                selector.expectedRegion?.let { bounds ->
                    output.writeBoolean(true)
                    output.writeInt(bounds.left.toRawBits())
                    output.writeInt(bounds.top.toRawBits())
                    output.writeInt(bounds.right.toRawBits())
                    output.writeInt(bounds.bottom.toRawBits())
                } ?: output.writeBoolean(false)
                output.writeBoolean(selector.requiresClickable)
                output.writeBoolean(selector.requiresVisible)
            }
        }

        writeString(id.value)
        with(environment) {
            writeString(deviceManufacturer)
            writeString(deviceModel)
            output.writeInt(androidSdk)
            writeString(androidBuildFingerprint)
            writeString(cameraPackage)
            output.writeLong(cameraVersionCode)
            writeString(localeTag)
            output.writeInt(displayWidthPx)
            output.writeInt(displayHeightPx)
            output.writeInt(densityDpi)
        }
        output.writeInt(selectorSchemaVersion)
        targets.toSortedMap(compareBy { it.name }).forEach { (action, set) ->
            writeString("action:${action.name}")
            writeSelectorSet(set)
        }
        speedTargets.toSortedMap(compareBy { it.name }).forEach { (speed, set) ->
            writeString("speed:${speed.name}")
            writeSelectorSet(set)
        }
        stateSignals.toSortedMap(compareBy { it.name }).forEach { (signal, set) ->
            writeString("signal:${signal.name}")
            writeSelectorSet(set)
        }
        fallbackGestures.toSortedMap(compareBy { it.name }).forEach { (action, gesture) ->
            writeString("gesture:${action.name}")
            output.writeInt(gesture.point.x.toRawBits())
            output.writeInt(gesture.point.y.toRawBits())
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes.toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

private const val SHA_256_HEX_LENGTH = 64
