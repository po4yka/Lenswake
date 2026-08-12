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
import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EnvironmentSnapshotCaptureResult
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.EnvironmentSnapshotRepository
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Validates exact-alarm identity and bridges a persisted execution plan into the engine. */
class DefaultAlarmTriggerCoordinator(
    private val scheduleRepository: ScheduleRepository,
    private val executionRepository: ExecutionRepository,
    private val environmentSnapshotRepository: EnvironmentSnapshotRepository,
    private val environmentSnapshotCollector: EnvironmentSnapshotCollector,
    private val automationEngine: AutomationEngine,
    private val startReadiness: suspend (ExecutionSession) -> Result<Unit>,
    private val clock: LenswakeClock,
    private val snapshotCollectionTimeoutMillis: Long = SNAPSHOT_COLLECTION_TIMEOUT_MILLIS,
    private val scheduleMutationMutex: Mutex = Mutex(),
) : AlarmTriggerCoordinator {
    init {
        require(snapshotCollectionTimeoutMillis > 0) { "Snapshot collection timeout must be positive" }
    }

    private val context = AlarmExecutionContext(scheduleRepository, executionRepository, clock)
    private val engineRunner = AlarmEngineRunner(automationEngine)
    private val snapshotResolver = EnvironmentSnapshotResolver(
        context = context,
        repository = environmentSnapshotRepository,
        collector = environmentSnapshotCollector,
        timeoutMillis = snapshotCollectionTimeoutMillis,
    )
    private val startHandler = StartAlarmHandler(
        context = context,
        snapshotResolver = snapshotResolver,
        engineRunner = engineRunner,
        startReadiness = startReadiness,
        mutex = scheduleMutationMutex,
    )
    private val stopHandler = StopAlarmHandler(context, engineRunner)

    override suspend fun handle(trigger: AlarmTrigger): AlarmHandlingResult = when (trigger.kind) {
        AlarmKind.START -> startHandler.handle(trigger)
        AlarmKind.STOP -> stopHandler.handle(trigger)
    }
}

private class AlarmExecutionContext(
    private val scheduleRepository: ScheduleRepository,
    val executionRepository: ExecutionRepository,
    val clock: LenswakeClock,
) {
    suspend fun loadSchedule(id: ScheduleId): Result<RecordingSchedule?> =
        captureNonCancellationException { scheduleRepository.get(id) }

    suspend fun loadExecution(id: SessionId): Result<ExecutionSession?> =
        captureNonCancellationException { executionRepository.get(id) }

    fun executionKey(schedule: RecordingSchedule): String =
        "schedule/${schedule.id.value}/${schedule.startAt.toEpochMilli()}"

    fun sessionId(executionKey: String): SessionId = SessionId(
        UUID.nameUUIDFromBytes(executionKey.toByteArray(StandardCharsets.UTF_8)).toString(),
    )

    fun snapshotId(sessionId: SessionId): EnvironmentSnapshotId = EnvironmentSnapshotId(
        UUID.nameUUIDFromBytes(
            "environment/${sessionId.value}".toByteArray(StandardCharsets.UTF_8),
        ).toString(),
    )

    fun validateTrigger(
        trigger: AlarmTrigger,
        schedule: RecordingSchedule,
    ): AlarmHandlingResult.TerminalRejected? {
        val expected = when (trigger.kind) {
            AlarmKind.START -> schedule.startAt
            AlarmKind.STOP -> schedule.stopAt
        }
        val structuralReason = when {
            !schedule.enabled -> "The alarm schedule is disabled"
            schedule.updatedAt != trigger.scheduleUpdatedAt ->
                "The alarm belongs to an obsolete schedule revision"
            expected != trigger.expectedAt ->
                "The alarm expected time does not match the persisted schedule"
            else -> null
        }
        return structuralReason?.let(::terminal) ?: run {
            val now = clock.now()
            val temporalReason = when {
                now.isBefore(expected) -> "The alarm was delivered before its expected time"
                trigger.kind == AlarmKind.START && !now.isBefore(schedule.stopAt) ->
                    "The START alarm arrived after the scheduled stop time"
                else -> null
            }
            temporalReason?.let(::terminal)
        }
    }

    fun validateExecution(
        session: ExecutionSession,
        schedule: RecordingSchedule,
        executionKey: String,
    ): AlarmHandlingResult.TerminalRejected? {
        val matchesSchedule = listOf(
            session.executionKey == executionKey,
            session.scheduleId == schedule.id,
            session.profileId == schedule.profileId,
            session.expectedStartAt == schedule.startAt,
            session.expectedStopAt == schedule.stopAt,
            session.capture == schedule.capture,
        ).all { it }
        return if (matchesSchedule) {
            null
        } else {
            terminal("The persisted execution does not match the current schedule intent")
        }
    }
}

private class StartAlarmHandler(
    private val context: AlarmExecutionContext,
    private val snapshotResolver: EnvironmentSnapshotResolver,
    private val engineRunner: AlarmEngineRunner,
    private val startReadiness: suspend (ExecutionSession) -> Result<Unit>,
    private val mutex: Mutex,
) {
    suspend fun handle(trigger: AlarmTrigger): AlarmHandlingResult {
        val admission = mutex.withLock { admit(trigger) }
        return when (admission) {
            is StartAdmission.Reserved -> continueStart(admission.schedule, admission.session)
            is StartAdmission.Failed -> admission.result
        }
    }

    private suspend fun admit(trigger: AlarmTrigger): StartAdmission =
        context.loadSchedule(trigger.scheduleId).fold(
            onSuccess = { schedule -> admitLoadedSchedule(trigger, schedule) },
            onFailure = { error ->
                StartAdmission.Failed(retryable("Could not load the alarm schedule", error))
            },
        )

    private suspend fun admitLoadedSchedule(
        trigger: AlarmTrigger,
        schedule: RecordingSchedule?,
    ): StartAdmission {
        val validation = schedule?.let { context.validateTrigger(trigger, it) }
        return when {
            schedule == null -> StartAdmission.Failed(
                terminal("The alarm schedule no longer exists"),
            )
            validation != null -> StartAdmission.Failed(validation)
            else -> reserve(schedule, createCandidate(schedule))
        }
    }

    private fun createCandidate(schedule: RecordingSchedule): ExecutionSession {
        val executionKey = context.executionKey(schedule)
        val now = context.clock.now()
        return ExecutionSession(
            id = context.sessionId(executionKey),
            executionKey = executionKey,
            kind = SessionKind.SCHEDULED,
            scheduleId = schedule.id,
            scheduleName = schedule.name,
            profileId = schedule.profileId,
            profileProvenance = schedule.profileProvenance,
            capture = schedule.capture,
            expectedStartAt = schedule.startAt,
            expectedStopAt = schedule.stopAt,
            alarmStartDeliveredAt = now,
            status = SessionStatus.PENDING,
            createdAt = now,
            updatedAt = now,
        )
    }

    private suspend fun reserve(
        schedule: RecordingSchedule,
        candidate: ExecutionSession,
    ): StartAdmission = captureNonCancellationException {
        context.executionRepository.reservePixelCamera(candidate)
    }.fold(
        onSuccess = { reservation ->
            when (reservation) {
                is ExecutionReservationResult.Reserved ->
                    StartAdmission.Reserved(schedule, reservation.session)
                is ExecutionReservationResult.CameraBusy -> StartAdmission.Failed(
                    terminal("Pixel Camera is owned by execution ${reservation.owner.id.value}"),
                )
            }
        },
        onFailure = { error ->
            StartAdmission.Failed(
                retryable("Could not reserve Pixel Camera for the execution", error),
            )
        },
    )

    private suspend fun continueStart(
        schedule: RecordingSchedule,
        session: ExecutionSession,
    ): AlarmHandlingResult {
        val validation = context.validateExecution(
            session,
            schedule,
            context.executionKey(schedule),
        )
        return when {
            validation != null -> validation
            session.cameraOwnershipReleasedAt != null -> AlarmHandlingResult.Accepted
            else -> continueValidatedStart(session)
        }
    }

    private suspend fun continueValidatedStart(session: ExecutionSession): AlarmHandlingResult {
        val readinessFailure = readinessFailure(session)
        return if (readinessFailure != null) {
            failReadiness(session, readinessFailure)
        } else {
            when (val snapshot = snapshotResolver.ensure(session)) {
                is SnapshotCheckpoint.Ready -> engineRunner.run(AlarmKind.START, snapshot.session.id)
                is SnapshotCheckpoint.Failed -> snapshot.result
            }
        }
    }

    private suspend fun readinessFailure(session: ExecutionSession): Throwable? =
        if (session.recordActionAt != null) {
            null
        } else {
            captureNonCancellationException { startReadiness(session) }.fold(
                onSuccess = Result<Unit>::exceptionOrNull,
                onFailure = { it },
            )
        }

    private suspend fun failReadiness(
        session: ExecutionSession,
        cause: Throwable,
    ): AlarmHandlingResult {
        if (session.revision == Long.MAX_VALUE) {
            return terminal("START readiness failed and the execution revision cannot advance")
        }
        val failedAt = maxOf(context.clock.now(), session.updatedAt)
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
        return captureNonCancellationException {
            context.executionRepository.apply(ExecutionChange(session.revision, failed), event)
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is ExecutionApplyResult.Applied -> terminal(failure.message)
                    is ExecutionApplyResult.RevisionConflict -> retryable(
                        "START readiness failure lost a concurrent execution transition",
                    )
                }
            },
            onFailure = { error ->
                retryable("Could not persist START readiness failure", error)
            },
        )
    }
}

private class EnvironmentSnapshotResolver(
    private val context: AlarmExecutionContext,
    private val repository: EnvironmentSnapshotRepository,
    private val collector: EnvironmentSnapshotCollector,
    private val timeoutMillis: Long,
) {
    suspend fun ensure(session: ExecutionSession): SnapshotCheckpoint =
        session.environmentSnapshotId?.let { resolveLinked(session, it) }
            ?: resolveUnlinked(session)

    private suspend fun resolveLinked(
        session: ExecutionSession,
        snapshotId: EnvironmentSnapshotId,
    ): SnapshotCheckpoint = captureNonCancellationException {
        repository.getEnvironmentSnapshot(snapshotId)
    }.fold(
        onSuccess = { snapshot ->
            if (snapshot?.sessionId == session.id) {
                SnapshotCheckpoint.Ready(session)
            } else {
                SnapshotCheckpoint.Failed(
                    terminal("Execution points to a missing environment snapshot"),
                )
            }
        },
        onFailure = { error ->
            SnapshotCheckpoint.Failed(
                retryable("Could not load the linked environment snapshot", error),
            )
        },
    )

    private suspend fun resolveUnlinked(session: ExecutionSession): SnapshotCheckpoint =
        captureNonCancellationException {
            repository.getEnvironmentSnapshotForSession(session.id)
        }.fold(
            onSuccess = { snapshot ->
                snapshot?.let { useExisting(session, it.id) } ?: collect(session)
            },
            onFailure = { error ->
                SnapshotCheckpoint.Failed(
                    retryable("Could not check for an existing environment snapshot", error),
                )
            },
        )

    private suspend fun useExisting(
        session: ExecutionSession,
        snapshotId: EnvironmentSnapshotId,
    ): SnapshotCheckpoint = context.loadExecution(session.id).fold(
        onSuccess = { linked ->
            if (linked?.environmentSnapshotId == snapshotId) {
                SnapshotCheckpoint.Ready(linked)
            } else {
                SnapshotCheckpoint.Failed(
                    terminal("Environment snapshot is not linked from its execution"),
                )
            }
        },
        onFailure = { error ->
            SnapshotCheckpoint.Failed(
                retryable("Could not reload the snapshotted execution", error),
            )
        },
    )

    private suspend fun collect(session: ExecutionSession): SnapshotCheckpoint {
        val snapshotId = context.snapshotId(session.id)
        val attempt = withTimeoutOrNull(timeoutMillis) {
            SnapshotCollectionAttempt.Completed(
                collector.collect(snapshotId, session.id),
            )
        } ?: SnapshotCollectionAttempt.TimedOut
        return when (attempt) {
            SnapshotCollectionAttempt.TimedOut -> SnapshotCheckpoint.Failed(
                retryable("Environment snapshot collection exceeded its finite timeout"),
            )
            is SnapshotCollectionAttempt.Completed -> processCollection(
                session = session,
                expectedId = snapshotId,
                result = attempt.result,
            )
        }
    }

    private suspend fun processCollection(
        session: ExecutionSession,
        expectedId: EnvironmentSnapshotId,
        result: Result<EnvironmentSnapshot>,
    ): SnapshotCheckpoint {
        val snapshot = result.getOrNull()
        return when {
            result.isFailure -> SnapshotCheckpoint.Failed(
                retryable("Could not capture the execution environment", result.exceptionOrNull()),
            )
            snapshot?.id != expectedId || snapshot.sessionId != session.id ->
                SnapshotCheckpoint.Failed(
                    terminal("Environment collector returned a mismatched snapshot"),
                )
            else -> persist(
                snapshot.copy(profileProvenance = session.profileProvenance),
                expectedId,
            )
        }
    }

    private suspend fun persist(
        snapshot: EnvironmentSnapshot,
        expectedId: EnvironmentSnapshotId,
    ): SnapshotCheckpoint = captureNonCancellationException {
        repository.capture(snapshot)
    }.fold(
        onSuccess = { capture ->
            when (capture) {
                is EnvironmentSnapshotCaptureResult.Captured -> validate(
                    expectedSnapshotId = expectedId,
                    snapshotSessionId = capture.snapshot.sessionId,
                    capturedSnapshotId = capture.snapshot.id,
                    session = capture.session,
                )
                is EnvironmentSnapshotCaptureResult.AlreadyExists -> validate(
                    expectedSnapshotId = capture.existing.id,
                    snapshotSessionId = capture.existing.sessionId,
                    capturedSnapshotId = capture.existing.id,
                    session = capture.session,
                )
            }
        },
        onFailure = { error ->
            SnapshotCheckpoint.Failed(
                retryable("Could not persist the execution environment", error),
            )
        },
    )

    private fun validate(
        expectedSnapshotId: EnvironmentSnapshotId,
        snapshotSessionId: SessionId,
        capturedSnapshotId: EnvironmentSnapshotId,
        session: ExecutionSession,
    ): SnapshotCheckpoint {
        val linkageIsConsistent = listOf(
            snapshotSessionId == session.id,
            capturedSnapshotId == expectedSnapshotId,
            session.environmentSnapshotId == capturedSnapshotId,
        ).all { it }
        return if (linkageIsConsistent) {
            SnapshotCheckpoint.Ready(session)
        } else {
            SnapshotCheckpoint.Failed(
                terminal("Persisted environment snapshot linkage is inconsistent"),
            )
        }
    }
}

private class StopAlarmHandler(
    private val context: AlarmExecutionContext,
    private val engineRunner: AlarmEngineRunner,
) {
    suspend fun handle(trigger: AlarmTrigger): AlarmHandlingResult =
        context.loadSchedule(trigger.scheduleId).fold(
            onSuccess = { schedule -> handleLoadedSchedule(trigger, schedule) },
            onFailure = { error -> retryable("Could not load the alarm schedule", error) },
        )

    private suspend fun handleLoadedSchedule(
        trigger: AlarmTrigger,
        schedule: RecordingSchedule?,
    ): AlarmHandlingResult {
        val validation = schedule?.let { context.validateTrigger(trigger, it) }
        return when {
            schedule == null -> terminal("The alarm schedule no longer exists")
            validation != null -> validation
            else -> {
                val executionKey = context.executionKey(schedule)
                when (val lookup = locate(schedule, context.sessionId(executionKey))) {
                    is ExecutionLookup.Failed -> lookup.result
                    is ExecutionLookup.Found -> {
                        val executionValidation =
                            context.validateExecution(lookup.session, schedule, executionKey)
                        executionValidation ?: deliverAndReconcile(lookup.session)
                    }
                }
            }
        }
    }

    private suspend fun locate(
        schedule: RecordingSchedule,
        deterministicId: SessionId,
    ): ExecutionLookup = captureNonCancellationException {
        context.executionRepository.findPixelCameraOwnerForSchedule(schedule.id)
    }.fold(
        onSuccess = { active ->
            active?.let(ExecutionLookup::Found) ?: context.loadExecution(deterministicId).fold(
                onSuccess = { session ->
                    session?.let(ExecutionLookup::Found)
                        ?: ExecutionLookup.Failed(
                            retryable("No persisted execution exists for this STOP alarm"),
                        )
                },
                onFailure = { error ->
                    ExecutionLookup.Failed(
                        retryable("Could not load the execution for this STOP alarm", error),
                    )
                },
            )
        },
        onFailure = { error ->
            ExecutionLookup.Failed(
                retryable("Could not locate the active execution session", error),
            )
        },
    )

    private suspend fun deliverAndReconcile(session: ExecutionSession): AlarmHandlingResult =
        when (val delivery = persistStopDelivery(session)) {
            is StopDeliveryResult.Failed -> delivery.result
            is StopDeliveryResult.Persisted -> when (val result = reconcile(delivery.session)) {
                StopReconciliation.NoCameraWork -> AlarmHandlingResult.Accepted
                is StopReconciliation.StopRequired ->
                    engineRunner.run(AlarmKind.STOP, result.session.id)
                is StopReconciliation.Failed -> result.result
                StopReconciliation.CancelWithoutOwnership ->
                    terminal("STOP reconciliation remained incomplete")
            }
        }

    private suspend fun persistStopDelivery(session: ExecutionSession): StopDeliveryResult = when {
        session.alarmStopDeliveredAt != null -> StopDeliveryResult.Persisted(session)
        session.revision == Long.MAX_VALUE -> StopDeliveryResult.Failed(
            terminal("STOP delivery cannot increment the execution revision"),
        )
        else -> persistNewStopDelivery(session)
    }

    private suspend fun persistNewStopDelivery(session: ExecutionSession): StopDeliveryResult {
        val deliveredAt = maxOf(context.clock.now(), session.updatedAt)
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
        return captureNonCancellationException {
            context.executionRepository.apply(
                change = ExecutionChange(session.revision, updated),
                event = event,
            )
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is ExecutionApplyResult.Applied -> StopDeliveryResult.Persisted(result.session)
                    is ExecutionApplyResult.RevisionConflict -> resolveStopDeliveryConflict(session.id)
                }
            },
            onFailure = { error ->
                StopDeliveryResult.Failed(
                    retryable("Could not persist STOP alarm delivery", error),
                )
            },
        )
    }

    private suspend fun resolveStopDeliveryConflict(sessionId: SessionId): StopDeliveryResult =
        context.loadExecution(sessionId).fold(
            onSuccess = { current ->
                if (current?.alarmStopDeliveredAt != null) {
                    StopDeliveryResult.Persisted(current)
                } else {
                    StopDeliveryResult.Failed(
                        retryable("STOP delivery lost a concurrent state transition"),
                    )
                }
            },
            onFailure = { error ->
                StopDeliveryResult.Failed(
                    retryable("Could not resolve concurrent STOP delivery", error),
                )
            },
        )

    private suspend fun reconcile(session: ExecutionSession): StopReconciliation =
        when (val initial = initialReconciliation(session)) {
            StopReconciliation.CancelWithoutOwnership -> persistCancellation(session)
            else -> initial
        }

    private fun initialReconciliation(session: ExecutionSession): StopReconciliation = when {
        session.status == SessionStatus.COMPLETED -> StopReconciliation.NoCameraWork
        session.stoppedVerifiedAt != null && session.shouldResumePostStopVerification() ->
            StopReconciliation.StopRequired(session)
        session.stoppedVerifiedAt != null -> StopReconciliation.NoCameraWork
        session.cameraOwnershipReleasedAt != null -> StopReconciliation.NoCameraWork
        session.recordActionAt != null -> StopReconciliation.StopRequired(session)
        session.status in TERMINAL_STATUSES -> StopReconciliation.NoCameraWork
        session.status !in PRE_RECORDING_STATUSES -> StopReconciliation.Failed(
            terminal("Persisted recording state has no Pixel Camera ownership checkpoint"),
        )
        session.revision == Long.MAX_VALUE -> StopReconciliation.Failed(
            terminal("STOP reconciliation cannot increment the execution revision"),
        )
        else -> StopReconciliation.CancelWithoutOwnership
    }

    private suspend fun persistCancellation(session: ExecutionSession): StopReconciliation {
        val reconciledAt = maxOf(context.clock.now(), session.updatedAt)
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
        return captureNonCancellationException {
            context.executionRepository.apply(
                ExecutionChange(session.revision, cancelled),
                event,
            )
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is ExecutionApplyResult.Applied -> StopReconciliation.NoCameraWork
                    is ExecutionApplyResult.RevisionConflict ->
                        resolveCancellationConflict(session.id)
                }
            },
            onFailure = { error ->
                StopReconciliation.Failed(
                    retryable("Could not persist STOP reconciliation", error),
                )
            },
        )
    }

    private suspend fun resolveCancellationConflict(sessionId: SessionId): StopReconciliation =
        context.loadExecution(sessionId).fold(
            onSuccess = { current ->
                when {
                    current == null -> StopReconciliation.Failed(
                        retryable("Execution disappeared during STOP reconciliation"),
                    )
                    current.stoppedVerifiedAt != null -> StopReconciliation.NoCameraWork
                    current.recordActionAt != null -> StopReconciliation.StopRequired(current)
                    current.status in TERMINAL_STATUSES -> StopReconciliation.NoCameraWork
                    else -> StopReconciliation.Failed(
                        retryable("STOP reconciliation lost a concurrent state transition"),
                    )
                }
            },
            onFailure = { error ->
                StopReconciliation.Failed(
                    retryable("Could not persist STOP reconciliation", error),
                )
            },
        )
}

private class AlarmEngineRunner(
    private val automationEngine: AutomationEngine,
) {
    suspend fun run(kind: AlarmKind, sessionId: SessionId): AlarmHandlingResult =
        captureNonCancellationException {
            when (kind) {
                AlarmKind.START -> automationEngine.start(sessionId)
                AlarmKind.STOP -> automationEngine.stop(sessionId)
            }
        }.fold(
            onSuccess = { result -> map(result, kind) },
            onFailure = { error ->
                retryable("$kind automation terminated unexpectedly", error)
            },
        )

    private fun map(
        result: AutomationRunResult,
        kind: AlarmKind,
    ): AlarmHandlingResult = when (result) {
        is AutomationRunResult.Succeeded,
        is AutomationRunResult.AlreadySatisfied,
        is AutomationRunResult.StopVerifiedAfterFailure,
        is AutomationRunResult.AlreadyTerminal,
        -> AlarmHandlingResult.Accepted
        is AutomationRunResult.NotFound ->
            retryable("The persisted execution disappeared before automation")
        is AutomationRunResult.Rejected ->
            terminal("$kind automation was rejected: ${result.failure.message}")
        is AutomationRunResult.Failed -> mapFailure(result, kind)
        is AutomationRunResult.StartReconciliationRequired -> retryable(
            "$kind automation requires recording-state reconciliation: ${result.failure.message}",
        )
        is AutomationRunResult.RevisionConflict ->
            retryable("$kind automation lost a concurrent state transition")
        is AutomationRunResult.PersistenceFailure -> retryable(
            "$kind automation could not persist its state: ${result.failure.message}",
        )
    }

    private fun mapFailure(
        result: AutomationRunResult.Failed,
        kind: AlarmKind,
    ): AlarmHandlingResult = if (
        kind == AlarmKind.STOP &&
        result.failure.code == AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED
    ) {
        retryable("STOP saved-media verification is not complete: ${result.failure.message}")
    } else {
        terminal("$kind automation failed: ${result.failure.message}")
    }
}

private suspend fun <T> captureNonCancellationException(
    block: suspend () -> T,
): Result<T> = runCatching { block() }.onFailure(::rethrowCancellationOrFatal)

private fun rethrowCancellationOrFatal(failure: Throwable) {
    if (failure is CancellationException) throw failure
    if (failure !is Exception) throw failure
}

private fun ExecutionSession.shouldResumePostStopVerification(): Boolean =
    !mediaVerificationRequired || hasPendingMediaVerificationCheckpoint()

private fun ExecutionSession.hasPendingMediaVerificationCheckpoint(): Boolean =
    mediaSavedVerifiedAt == null &&
        mediaBaselineGeneration != null &&
        mediaStoreVersion != null

private fun terminal(reason: String): AlarmHandlingResult.TerminalRejected =
    AlarmHandlingResult.TerminalRejected(reason)

private fun retryable(
    reason: String,
    cause: Throwable? = null,
): AlarmHandlingResult.Retryable = AlarmHandlingResult.Retryable(reason, cause)

private sealed interface ExecutionLookup {
    data class Found(val session: ExecutionSession) : ExecutionLookup
    data class Failed(val result: AlarmHandlingResult) : ExecutionLookup
}

private sealed interface StopDeliveryResult {
    data class Persisted(val session: ExecutionSession) : StopDeliveryResult
    data class Failed(val result: AlarmHandlingResult) : StopDeliveryResult
}

private sealed interface StartAdmission {
    data class Reserved(val schedule: RecordingSchedule, val session: ExecutionSession) : StartAdmission
    data class Failed(val result: AlarmHandlingResult) : StartAdmission
}

private sealed interface StopReconciliation {
    data object NoCameraWork : StopReconciliation
    data object CancelWithoutOwnership : StopReconciliation
    data class StopRequired(val session: ExecutionSession) : StopReconciliation
    data class Failed(val result: AlarmHandlingResult) : StopReconciliation
}

private sealed interface SnapshotCheckpoint {
    data class Ready(val session: ExecutionSession) : SnapshotCheckpoint
    data class Failed(val result: AlarmHandlingResult) : SnapshotCheckpoint
}

private sealed interface SnapshotCollectionAttempt {
    data object TimedOut : SnapshotCollectionAttempt
    data class Completed(val result: Result<EnvironmentSnapshot>) : SnapshotCollectionAttempt
}

private const val STOP_DELIVERED_EVENT = "automation.alarm.stop_delivered"
private const val STOP_RECONCILED_WITHOUT_OWNERSHIP_EVENT =
    "automation.alarm.stop_reconciled_without_ownership"
private const val SNAPSHOT_COLLECTION_TIMEOUT_MILLIS = 5_000L
private val TERMINAL_STATUSES = setOf(
    SessionStatus.COMPLETED,
    SessionStatus.FAILED,
    SessionStatus.CANCELLED,
)
private val PRE_RECORDING_STATUSES = setOf(SessionStatus.PENDING, SessionStatus.STARTING)
