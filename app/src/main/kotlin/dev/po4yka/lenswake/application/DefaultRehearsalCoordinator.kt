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
import dev.po4yka.lenswake.core.CaptureConfiguration
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
    private val preparationPersistence =
        RehearsalPreparationPersistence(
            executionRepository = executionRepository,
            environmentSnapshotRepository = environmentSnapshotRepository,
            environmentSnapshotCollector = environmentSnapshotCollector,
            backstop = backstop,
            stopWorkflow = stopWorkflow,
            clock = clock,
        )
    private val startWorkflow =
        RehearsalStartWorkflow(
            profileRepository = profileRepository,
            executionRepository = executionRepository,
            environmentProbe = environmentProbe,
            automationEngine = automationEngine,
            backstop = backstop,
            clock = clock,
            preparationPersistence = preparationPersistence,
        )

    override suspend fun run(request: RehearsalRequest): RehearsalResult =
        when (val preparation = mutex.withLock { startWorkflow.prepare(request) }) {
            is StartPreparation.Result -> preparation.value
            is StartPreparation.Started -> finishStarted(request, preparation)
        }

    private suspend fun finishStarted(
        request: RehearsalRequest,
        started: StartPreparation.Started,
    ): RehearsalResult =
        if (started.result.hasVerifiedRecordingOwnership()) {
            waitAndStop(request, started)
        } else {
            handleFailedStart(started.sessionId, started.result)
        }

    private suspend fun waitAndStop(
        request: RehearsalRequest,
        started: StartPreparation.Started,
    ): RehearsalResult {
        val waitFailure =
            runCatching {
                rehearsalDelay.wait(request.recordingDuration)
            }.exceptionOrNull()
        return when (waitFailure) {
            null -> {
                stopWorkflow.stopInline(started.sessionId).toRehearsalResult(started.sessionId)
            }

            is CancellationException -> {
                boundedSafetyStop(stopWorkflow, started.sessionId)
                throw waitFailure
            }

            is Exception -> {
                handleDelayFailure(started.sessionId, waitFailure)
            }

            else -> {
                throw waitFailure
            }
        }
    }

    private suspend fun handleDelayFailure(
        sessionId: SessionId,
        failure: Throwable,
    ): RehearsalResult =
        when (val cleanup = boundedSafetyStop(stopWorkflow, sessionId)) {
            is RehearsalStopOutcome.Promoted -> {
                RehearsalResult.Completed(cleanup.session, cleanup.profile)
            }

            is RehearsalStopOutcome.SafeFailure -> {
                RehearsalSupport.rejected(
                    RehearsalResultCode.STOP_FAILED,
                    failure.message ?: "Rehearsal delay failed",
                    sessionId,
                )
            }

            is RehearsalStopOutcome.Invalid,
            is RehearsalStopOutcome.Retryable,
            -> {
                RehearsalResult.SafetyStopPending(
                    sessionId,
                    "Rehearsal delay failed and STOP remains unverified; backstop retained",
                )
            }
        }

    private fun RehearsalStopOutcome.toRehearsalResult(sessionId: SessionId): RehearsalResult =
        when (this) {
            is RehearsalStopOutcome.Promoted -> {
                RehearsalResult.Completed(
                    session,
                    profile,
                )
            }

            is RehearsalStopOutcome.SafeFailure -> {
                RehearsalResult.Rejected(
                    code = RehearsalResultCode.STOP_FAILED,
                    message = message,
                    sessionId = sessionId,
                )
            }

            is RehearsalStopOutcome.Retryable -> {
                RehearsalResult.SafetyStopPending(
                    sessionId = sessionId,
                    message = message,
                )
            }

            is RehearsalStopOutcome.Invalid -> {
                RehearsalResult.Rejected(
                    code = RehearsalResultCode.INVALID_STOP_TRIGGER,
                    message = message,
                    sessionId = sessionId,
                )
            }
        }

    private suspend fun handleFailedStart(
        sessionId: SessionId,
        result: AutomationRunResult,
    ): RehearsalResult {
        val current = executionRepository.get(sessionId)
        val hasOwnership = current?.recordActionAt != null && current.stoppedVerifiedAt == null
        if (!hasOwnership) {
            preparationPersistence.failBeforeDispatch(sessionId, result.failureMessage())
            preparationPersistence.boundedCancelBackstop(sessionId)
            return RehearsalSupport.rejected(
                RehearsalResultCode.START_FAILED,
                result.failureMessage(),
                sessionId,
            )
        }

        val cleanup = boundedSafetyStop(stopWorkflow, sessionId)
        return when (cleanup) {
            is RehearsalStopOutcome.SafeFailure -> {
                RehearsalSupport.rejected(
                    RehearsalResultCode.START_FAILED,
                    result.failureMessage(),
                    sessionId,
                )
            }

            is RehearsalStopOutcome.Promoted -> {
                RehearsalResult.Completed(cleanup.session, cleanup.profile)
            }

            is RehearsalStopOutcome.Invalid,
            is RehearsalStopOutcome.Retryable,
            -> {
                RehearsalResult.SafetyStopPending(
                    sessionId,
                    "START was not successful and recording ownership remains possible; STOP backstop retained",
                )
            }
        }
    }
}

private class RehearsalStartWorkflow(
    private val profileRepository: AutomationProfileRepository,
    private val executionRepository: ExecutionRepository,
    private val environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    private val automationEngine: AutomationEngine,
    private val backstop: RehearsalStopBackstop,
    private val clock: LenswakeClock,
    private val preparationPersistence: RehearsalPreparationPersistence,
) {
    suspend fun prepare(request: RehearsalRequest): StartPreparation =
        when (val profile = loadProfile(request)) {
            is WorkflowStep.Outcome -> profile.value
            is WorkflowStep.Ready -> prepareForProfile(request, profile.value)
        }

    private suspend fun loadProfile(request: RehearsalRequest): WorkflowStep<PixelCameraProfile> =
        persistence("load profile") { profileRepository.get(request.profileId) }.fold(
            onSuccess = { profile ->
                if (profile == null) {
                    WorkflowStep.Outcome(
                        StartPreparation.Result(
                            RehearsalSupport.rejected(
                                RehearsalResultCode.PROFILE_NOT_FOUND,
                                "Pixel Camera profile was not found",
                            ),
                        ),
                    )
                } else {
                    WorkflowStep.Ready(profile)
                }
            },
            onFailure = { failure ->
                WorkflowStep.Outcome(
                    StartPreparation.Result(
                        RehearsalSupport.rejected(
                            RehearsalResultCode.SESSION_PERSISTENCE_FAILED,
                            failure.message ?: "Read failed",
                        ),
                    ),
                )
            },
        )

    private suspend fun prepareForProfile(
        request: RehearsalRequest,
        profile: PixelCameraProfile,
    ): StartPreparation =
        when (val environment = matchingEnvironment(profile)) {
            is WorkflowStep.Outcome -> environment.value
            is WorkflowStep.Ready -> reserve(request, profile, environment.value)
        }

    private fun matchingEnvironment(profile: PixelCameraProfile): WorkflowStep<PixelCameraEnvironment> =
        when (val observed = RehearsalSupport.inspectEnvironment(environmentProbe)) {
            is PortResult.Unavailable -> {
                WorkflowStep.Outcome(
                    StartPreparation.Result(
                        RehearsalSupport.rejected(
                            RehearsalResultCode.ENVIRONMENT_UNAVAILABLE,
                            observed.failure.message,
                        ),
                    ),
                )
            }

            is PortResult.Observed -> {
                if (profile.environment == observed.value) {
                    WorkflowStep.Ready(observed.value)
                } else {
                    WorkflowStep.Outcome(
                        StartPreparation.Result(
                            RehearsalSupport.rejected(
                                RehearsalResultCode.ENVIRONMENT_MISMATCH,
                                "The profile does not exactly match the current Pixel Camera environment",
                            ),
                        ),
                    )
                }
            }
        }

    private suspend fun reserve(
        request: RehearsalRequest,
        profile: PixelCameraProfile,
        environment: PixelCameraEnvironment,
    ): StartPreparation {
        val createdAt = clock.now()
        val sessionId = SessionId.new()
        val session =
            ExecutionSession(
                id = sessionId,
                executionKey = "rehearsal/${sessionId.value}/${profile.definitionFingerprint()}",
                kind = SessionKind.REHEARSAL,
                scheduleId = null,
                scheduleName = "Rehearsal",
                profileId = profile.id,
                capture = request.capture,
                expectedStartAt = createdAt,
                expectedStopAt = createdAt.plus(START_BUDGET).plus(request.recordingDuration).plus(STOP_MARGIN),
                status = SessionStatus.PENDING,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        return persistence("reserve Pixel Camera for rehearsal") {
            executionRepository.reservePixelCamera(session)
        }.fold(
            onSuccess = { reservation -> reservationResult(reservation, environment) },
            onFailure = { failure ->
                StartPreparation.Result(
                    RehearsalSupport.rejected(
                        RehearsalResultCode.SESSION_PERSISTENCE_FAILED,
                        failure.message ?: "Session create failed",
                        sessionId,
                    ),
                )
            },
        )
    }

    private suspend fun reservationResult(
        reservation: ExecutionReservationResult,
        environment: PixelCameraEnvironment,
    ): StartPreparation =
        when (reservation) {
            is ExecutionReservationResult.CameraBusy -> {
                StartPreparation.Result(
                    RehearsalResult.Busy(reservation.owner.id),
                )
            }

            is ExecutionReservationResult.Reserved -> {
                snapshotAndArm(reservation.session, environment)
            }
        }

    private suspend fun snapshotAndArm(
        session: ExecutionSession,
        environment: PixelCameraEnvironment,
    ): StartPreparation {
        val snapshotted =
            preparationPersistence.runStep(session.id) {
                preparationPersistence.captureSnapshot(session, environment)
            }
        return snapshotted.fold(
            onSuccess = { armBackstop(it) },
            onFailure = { failure ->
                preparationPersistence.failBeforeDispatch(
                    session.id,
                    failure.message ?: "Snapshot capture failed",
                )
                StartPreparation.Result(
                    RehearsalSupport.rejected(
                        RehearsalResultCode.SNAPSHOT_CAPTURE_FAILED,
                        failure.message ?: "Snapshot capture failed",
                        session.id,
                    ),
                )
            },
        )
    }

    private suspend fun armBackstop(session: ExecutionSession): StartPreparation {
        val armed =
            preparationPersistence.runStep(session.id) {
                backstop.schedule(session.id)
            }
        return if (armed.isSuccess) {
            startAutomation(session.id)
        } else {
            preparationPersistence.failBeforeDispatch(session.id, "Rehearsal STOP backstop could not be armed")
            preparationPersistence.boundedCancelBackstop(session.id)
            StartPreparation.Result(
                RehearsalSupport.rejected(
                    RehearsalResultCode.BACKSTOP_UNAVAILABLE,
                    "An exact independent STOP backstop is required before rehearsal START",
                    session.id,
                ),
            )
        }
    }

    private suspend fun startAutomation(sessionId: SessionId): StartPreparation {
        val attempt =
            runCatching {
                withTimeout(START_BUDGET.toMillis()) {
                    automationEngine.start(sessionId)
                }
            }
        val failure = attempt.exceptionOrNull()
        val result =
            when (failure) {
                null -> {
                    checkNotNull(attempt.getOrNull())
                }

                is TimeoutCancellationException -> {
                    startFailure(
                        sessionId,
                        AutomationFailureCode.AUTOMATION_TIMEOUT,
                        "Rehearsal START exceeded its finite safety budget",
                    )
                }

                is CancellationException -> {
                    preparationPersistence.handleCancelledPreparation(sessionId)
                    throw failure
                }

                is Exception -> {
                    startFailure(
                        sessionId,
                        AutomationFailureCode.UNKNOWN,
                        "Rehearsal START terminated unexpectedly",
                    )
                }

                else -> {
                    throw failure
                }
            }
        return StartPreparation.Started(sessionId, result)
    }

    private suspend fun startFailure(
        sessionId: SessionId,
        code: AutomationFailureCode,
        message: String,
    ): AutomationRunResult =
        AutomationRunResult.PersistenceFailure(
            session = executionRepository.get(sessionId),
            failure = AutomationFailure(code, message),
        )
}

private class RehearsalPreparationPersistence(
    private val executionRepository: ExecutionRepository,
    private val environmentSnapshotRepository: EnvironmentSnapshotRepository,
    private val environmentSnapshotCollector: EnvironmentSnapshotCollector,
    private val backstop: RehearsalStopBackstop,
    private val stopWorkflow: RehearsalStopWorkflow,
    private val clock: LenswakeClock,
) {
    suspend fun captureSnapshot(
        session: ExecutionSession,
        expectedEnvironment: PixelCameraEnvironment,
    ): Result<ExecutionSession> =
        persistence("capture environment snapshot") {
            val snapshotId = RehearsalSupport.deterministicSnapshotId(session.id)
            val snapshot =
                withTimeout(SNAPSHOT_TIMEOUT.toMillis()) {
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

    suspend fun <T> runStep(
        sessionId: SessionId,
        block: suspend () -> T,
    ): T {
        val attempt = runCatching { block() }
        return when (val failure = attempt.exceptionOrNull()) {
            null -> {
                checkNotNull(attempt.getOrNull())
            }

            is CancellationException -> {
                handleCancelledPreparation(sessionId)
                throw failure
            }

            else -> {
                throw failure
            }
        }
    }

    suspend fun handleCancelledPreparation(sessionId: SessionId) {
        withContext(NonCancellable) {
            val current = runCatching { executionRepository.get(sessionId) }.getOrNull()
            when {
                current == null -> {
                    Unit
                }

                current.recordActionAt != null && current.stoppedVerifiedAt == null -> {
                    boundedSafetyStop(stopWorkflow, sessionId)
                }

                else -> {
                    failBeforeDispatch(sessionId, "Rehearsal preparation was cancelled")
                    boundedCancelBackstop(sessionId)
                }
            }
        }
    }

    suspend fun failBeforeDispatch(
        sessionId: SessionId,
        message: String,
    ) {
        val session = executionRepository.get(sessionId)
        if (session != null && session.recordActionAt == null && session.status !in TERMINAL_STATUSES) {
            persistTransition(
                session = session,
                updated =
                    session.copy(
                        status = SessionStatus.FAILED,
                        currentAutomationState = AutomationStateName.FAILED,
                        failure =
                            AutomationFailure(
                                AutomationFailureCode.UNKNOWN,
                                message.ifBlank { "Rehearsal failed" },
                            ),
                    ),
                eventName = "automation.rehearsal.failed_before_dispatch",
                outcome = AutomationOutcome.FAILED,
            )
        }
    }

    suspend fun boundedCancelBackstop(sessionId: SessionId) {
        withContext(NonCancellable) {
            runCatching {
                withTimeout(BACKSTOP_CANCEL_TIMEOUT.toMillis()) {
                    backstop.cancel(sessionId).getOrThrow()
                }
            }
        }
    }

    private suspend fun persistTransition(
        session: ExecutionSession,
        updated: ExecutionSession,
        eventName: String,
        outcome: AutomationOutcome,
    ): ExecutionSession? {
        val applicable = session.revision != Long.MAX_VALUE
        return if (applicable) {
            val now = maxOf(clock.now(), session.updatedAt)
            val next = updated.copy(revision = session.revision + 1, updatedAt = now)
            val event =
                AutomationEvent(
                    id = EventId.new(),
                    sessionId = session.id,
                    name = eventName,
                    sequence = next.revision,
                    timestamp = now,
                    state = next.currentAutomationState ?: AutomationStateName.FAILED,
                    outcome = outcome,
                    failure = next.failure,
                )
            when (val applied = executionRepository.apply(ExecutionChange(session.revision, next), event)) {
                is ExecutionApplyResult.Applied -> applied.session
                is ExecutionApplyResult.RevisionConflict -> null
            }
        } else {
            null
        }
    }
}

private sealed interface WorkflowStep<out T> {
    data class Ready<T>(
        val value: T,
    ) : WorkflowStep<T>

    data class Outcome(
        val value: StartPreparation.Result,
    ) : WorkflowStep<Nothing>
}

private sealed interface StartPreparation {
    data class Started(
        val sessionId: SessionId,
        val result: AutomationRunResult,
    ) : StartPreparation

    data class Result(
        val value: RehearsalResult,
    ) : StartPreparation
}

private suspend inline fun <T> persistence(
    stage: String,
    crossinline block: suspend () -> T,
): Result<T> =
    runCatching { block() }
        .onFailure { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
        }.fold(
            onSuccess = Result.Companion::success,
            onFailure = { failure -> Result.failure(IllegalStateException("$stage failed", failure)) },
        )

private suspend fun boundedSafetyStop(
    stopWorkflow: RehearsalStopWorkflow,
    sessionId: SessionId,
): RehearsalStopOutcome =
    withContext(NonCancellable) {
        val attempt =
            runCatching {
                withTimeout(CLEANUP_TIMEOUT.toMillis()) {
                    stopWorkflow.stopSafety(sessionId)
                }
            }
        when (val failure = attempt.exceptionOrNull()) {
            null -> {
                checkNotNull(attempt.getOrNull())
            }

            is TimeoutCancellationException -> {
                RehearsalStopOutcome.Retryable("Safety STOP cleanup exceeded its finite timeout")
            }

            is Exception -> {
                RehearsalStopOutcome.Retryable(failure.message ?: "Safety STOP cleanup failed")
            }

            else -> {
                throw failure
            }
        }
    }

private val START_BUDGET: Duration = Duration.ofSeconds(60)
private val STOP_MARGIN: Duration = Duration.ofSeconds(30)
private val SNAPSHOT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val CLEANUP_TIMEOUT: Duration = Duration.ofSeconds(20)
private val BACKSTOP_CANCEL_TIMEOUT: Duration = Duration.ofSeconds(5)
private val TERMINAL_STATUSES = setOf(SessionStatus.COMPLETED, SessionStatus.FAILED, SessionStatus.CANCELLED)

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
    executionRepository: ExecutionRepository,
    environmentSnapshotRepository: EnvironmentSnapshotRepository,
    profileRepository: AutomationProfileRepository,
    environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    private val automationEngine: AutomationEngine,
    private val backstop: RehearsalStopBackstop,
    clock: LenswakeClock,
    private val mutex: Mutex,
) {
    private val persistence = RehearsalStopPersistence(executionRepository, clock)
    private val promotion =
        RehearsalPromotionWorkflow(
            environmentSnapshotRepository = environmentSnapshotRepository,
            profileRepository = profileRepository,
            environmentProbe = environmentProbe,
            backstop = backstop,
        )

    suspend fun stopInline(sessionId: SessionId): RehearsalStopOutcome =
        mutex.withLock {
            stopLocked(sessionId, alarmTrigger = null, promotionAllowed = true)
        }

    suspend fun stopSafety(sessionId: SessionId): RehearsalStopOutcome =
        mutex.withLock {
            stopLocked(sessionId, alarmTrigger = null, promotionAllowed = false)
        }

    suspend fun stopAlarm(trigger: RehearsalStopTrigger): RehearsalStopOutcome =
        mutex.withLock {
            stopLocked(trigger.sessionId, alarmTrigger = trigger, promotionAllowed = true)
        }

    private suspend fun stopLocked(
        sessionId: SessionId,
        alarmTrigger: RehearsalStopTrigger?,
        promotionAllowed: Boolean,
    ): RehearsalStopOutcome =
        when (val prepared = prepareStop(sessionId, alarmTrigger)) {
            is StopStep.Outcome -> prepared.value
            is StopStep.Ready -> finishPrepared(prepared.value, promotionAllowed)
        }

    private suspend fun prepareStop(
        sessionId: SessionId,
        alarmTrigger: RehearsalStopTrigger?,
    ): StopStep<ExecutionSession> =
        when (val loaded = persistence.load(sessionId)) {
            is StopStep.Outcome -> loaded
            is StopStep.Ready -> validateAlarm(loaded.value, alarmTrigger)
        }

    private suspend fun validateAlarm(
        session: ExecutionSession,
        alarmTrigger: RehearsalStopTrigger?,
    ): StopStep<ExecutionSession> =
        when {
            session.kind != SessionKind.REHEARSAL -> {
                StopStep.Outcome(RehearsalStopOutcome.Invalid("STOP target is not a rehearsal session"))
            }

            alarmTrigger == null -> {
                StopStep.Ready(session)
            }

            alarmTrigger.expectedAt != session.expectedStopAt -> {
                StopStep.Outcome(RehearsalStopOutcome.Invalid("Rehearsal STOP expected time is stale"))
            }

            persistence.isEarly(alarmTrigger) -> {
                StopStep.Outcome(
                    RehearsalStopOutcome.Invalid("Rehearsal STOP alarm arrived before its expected time"),
                )
            }

            else -> {
                persistence.persistAlarmDelivery(session)?.let(StopStep<ExecutionSession>::Ready)
                    ?: StopStep.Outcome(
                        RehearsalStopOutcome.Retryable("Could not persist rehearsal STOP delivery"),
                    )
            }
        }

    private suspend fun finishPrepared(
        session: ExecutionSession,
        promotionAllowed: Boolean,
    ): RehearsalStopOutcome =
        when {
            session.status == SessionStatus.COMPLETED && promotionAllowed -> {
                promotion.finalizeCompleted(session.id)
            }

            session.status == SessionStatus.COMPLETED -> {
                cancelSafelyStopped(backstop, session)
            }

            session.status in TERMINAL_STOP_STATUSES && session.hasCompleteStopProof -> {
                cancelSafelyStopped(backstop, session)
            }

            session.stoppedVerifiedAt != null && !session.awaitsMediaSaveVerification -> {
                cancelSafelyStopped(backstop, session)
            }

            session.cameraOwnershipReleasedAt != null || session.recordActionAt == null -> {
                cancelWithoutOwnership(session)
            }

            else -> {
                dispatchStop(session, promotionAllowed)
            }
        }

    private suspend fun dispatchStop(
        session: ExecutionSession,
        promotionAllowed: Boolean,
    ): RehearsalStopOutcome =
        when (val stopped = stopEngine(session.id)) {
            is StopStep.Outcome -> stopped.value
            is StopStep.Ready -> finishEngineResult(session.id, stopped.value, promotionAllowed)
        }

    private suspend fun stopEngine(sessionId: SessionId): StopStep<AutomationRunResult> =
        preservingCancellation { automationEngine.stop(sessionId) }.fold(
            onSuccess = StopStep<AutomationRunResult>::Ready,
            onFailure = { failure ->
                StopStep.Outcome(
                    RehearsalStopOutcome.Retryable(
                        failure.message ?: "Rehearsal STOP terminated unexpectedly",
                    ),
                )
            },
        )

    private suspend fun finishEngineResult(
        originalSessionId: SessionId,
        result: AutomationRunResult,
        promotionAllowed: Boolean,
    ): RehearsalStopOutcome {
        val currentSessionId = result.sessionOrNull()?.id ?: originalSessionId
        return when (val current = persistence.reloadAfterStop(currentSessionId)) {
            is StopStep.Outcome -> {
                current.value
            }

            is StopStep.Ready -> {
                when {
                    current.value.status == SessionStatus.COMPLETED && promotionAllowed -> {
                        promotion.finalizeCompleted(current.value.id)
                    }

                    current.value.mediaSavedVerifiedAt != null -> {
                        cancelSafelyStopped(backstop, current.value)
                    }

                    else -> {
                        RehearsalStopOutcome.Retryable(result.failureMessage())
                    }
                }
            }
        }
    }

    private suspend fun cancelWithoutOwnership(session: ExecutionSession): RehearsalStopOutcome {
        val released =
            if (session.cameraOwnershipReleasedAt != null) {
                session
            } else {
                persistence.releaseWithoutRecording(session)
                    ?: return RehearsalStopOutcome.Retryable(
                        "Could not release rehearsal ownership before cancelling its STOP backstop",
                    )
            }
        return if (backstop.cancel(released.id).isSuccess) {
            RehearsalStopOutcome.SafeFailure(
                released,
                "Rehearsal never acquired recording ownership; no Camera STOP was dispatched",
            )
        } else {
            RehearsalStopOutcome.Retryable("No recording ownership exists, but backstop cancellation failed")
        }
    }
}

private class RehearsalStopPersistence(
    private val executionRepository: ExecutionRepository,
    private val clock: LenswakeClock,
) {
    suspend fun load(sessionId: SessionId): StopStep<ExecutionSession> =
        preservingCancellation { executionRepository.get(sessionId) }.fold(
            onSuccess = { session ->
                session?.let(StopStep<ExecutionSession>::Ready)
                    ?: StopStep.Outcome(RehearsalStopOutcome.Invalid("Rehearsal session was not found"))
            },
            onFailure = {
                StopStep.Outcome(RehearsalStopOutcome.Retryable("Could not load rehearsal session"))
            },
        )

    fun isEarly(trigger: RehearsalStopTrigger): Boolean = clock.now().isBefore(trigger.expectedAt)

    suspend fun persistAlarmDelivery(session: ExecutionSession): ExecutionSession? =
        when {
            session.alarmStopDeliveredAt != null -> session
            session.revision == Long.MAX_VALUE -> null
            else -> applyAlarmDelivery(session)
        }

    suspend fun reloadAfterStop(sessionId: SessionId): StopStep<ExecutionSession> =
        preservingCancellation { executionRepository.get(sessionId) }.fold(
            onSuccess = { session ->
                session?.let(StopStep<ExecutionSession>::Ready)
                    ?: StopStep.Outcome(RehearsalStopOutcome.Retryable("Rehearsal disappeared after STOP"))
            },
            onFailure = {
                StopStep.Outcome(RehearsalStopOutcome.Retryable("Could not reload rehearsal after STOP"))
            },
        )

    suspend fun releaseWithoutRecording(session: ExecutionSession): ExecutionSession? =
        if (session.revision == Long.MAX_VALUE) {
            null
        } else {
            preservingCancellation { applyOwnershipRelease(session) }.getOrNull()
        }

    private suspend fun applyAlarmDelivery(session: ExecutionSession): ExecutionSession? {
        val now = maxOf(clock.now(), session.updatedAt)
        val updated =
            session.copy(
                alarmStopDeliveredAt = now,
                revision = session.revision + 1,
                updatedAt = now,
            )
        val event =
            AutomationEvent(
                id = EventId.new(),
                sessionId = session.id,
                name = "automation.rehearsal.stop_alarm_delivered",
                sequence = updated.revision,
                timestamp = now,
                state = AutomationStateName.STOP_TRIGGERED,
                outcome = AutomationOutcome.SUCCEEDED,
            )
        return when (executionRepository.apply(ExecutionChange(session.revision, updated), event)) {
            is ExecutionApplyResult.Applied -> {
                updated
            }

            is ExecutionApplyResult.RevisionConflict -> {
                executionRepository.get(session.id)?.takeIf {
                    it.alarmStopDeliveredAt != null
                }
            }
        }
    }

    private suspend fun applyOwnershipRelease(session: ExecutionSession): ExecutionSession? {
        val releasedAt = maxOf(clock.now(), session.updatedAt)
        val status = session.status.takeIf { it in TERMINAL_STOP_STATUSES } ?: SessionStatus.FAILED
        val cancelled = status == SessionStatus.CANCELLED
        val state = if (cancelled) AutomationStateName.CANCELLED else AutomationStateName.FAILED
        val outcome = if (cancelled) AutomationOutcome.CANCELLED else AutomationOutcome.FAILED
        val failure = session.failure ?: RehearsalSupport.ownershipReleaseFailure(cancelled)
        val released =
            session.copy(
                status = status,
                currentAutomationState = state,
                cameraOwnershipReleasedAt = releasedAt,
                failure = failure,
                revision = session.revision + 1,
                updatedAt = releasedAt,
            )
        val event =
            AutomationEvent(
                id = EventId.new(),
                sessionId = session.id,
                name = "automation.rehearsal.closed_without_recording",
                sequence = released.revision,
                timestamp = releasedAt,
                state = state,
                outcome = outcome,
                failure = failure,
            )
        return when (val applied = executionRepository.apply(ExecutionChange(session.revision, released), event)) {
            is ExecutionApplyResult.Applied -> applied.session
            is ExecutionApplyResult.RevisionConflict -> null
        }
    }
}

private class RehearsalPromotionWorkflow(
    private val environmentSnapshotRepository: EnvironmentSnapshotRepository,
    private val profileRepository: AutomationProfileRepository,
    private val environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    private val backstop: RehearsalStopBackstop,
) {
    suspend fun finalizeCompleted(sessionId: SessionId): RehearsalStopOutcome =
        when (val report = loadReport(sessionId)) {
            is StopStep.Outcome -> report.value
            is StopStep.Ready -> validateAndPromote(report.value)
        }

    private suspend fun loadReport(sessionId: SessionId): StopStep<ExecutionReport> =
        preservingCancellation { environmentSnapshotRepository.report(sessionId) }.fold(
            onSuccess = { report ->
                report?.let(StopStep<ExecutionReport>::Ready)
                    ?: StopStep.Outcome(RehearsalStopOutcome.Retryable("Rehearsal report is missing"))
            },
            onFailure = {
                StopStep.Outcome(RehearsalStopOutcome.Retryable("Could not load rehearsal proof"))
            },
        )

    private suspend fun validateAndPromote(report: ExecutionReport): RehearsalStopOutcome =
        when (val proofFailure = RehearsalSupport.validatePromotionProof(report)) {
            null -> {
                when (val profile = loadProfile(report)) {
                    is StopStep.Outcome -> profile.value
                    is StopStep.Ready -> promoteProfile(report, profile.value)
                }
            }

            else -> {
                cancelSafelyStopped(
                    backstop,
                    report.session,
                    "Rehearsal stopped safely but did not qualify for profile promotion: $proofFailure",
                )
            }
        }

    private suspend fun loadProfile(report: ExecutionReport): StopStep<PixelCameraProfile> =
        preservingCancellation { profileRepository.get(report.session.profileId) }.fold(
            onSuccess = { profile ->
                profile?.let(StopStep<PixelCameraProfile>::Ready)
                    ?: StopStep.Outcome(RehearsalStopOutcome.Retryable("Rehearsal profile is missing"))
            },
            onFailure = {
                StopStep.Outcome(RehearsalStopOutcome.Retryable("Could not load rehearsal profile"))
            },
        )

    private suspend fun promoteProfile(
        report: ExecutionReport,
        profile: PixelCameraProfile,
    ): RehearsalStopOutcome =
        when {
            report.session.testedProfileFingerprint() != profile.definitionFingerprint() -> {
                cancelSafelyStopped(
                    backstop,
                    report.session,
                    "Rehearsal stopped safely, but the profile definition changed before promotion",
                )
            }

            else -> {
                when (val environment = verifyEnvironment(report, profile)) {
                    is StopStep.Outcome -> environment.value
                    is StopStep.Ready -> persistVerifiedProfile(report, profile)
                }
            }
        }

    private fun verifyEnvironment(
        report: ExecutionReport,
        profile: PixelCameraProfile,
    ): StopStep<Unit> =
        when (val observed = RehearsalSupport.inspectEnvironment(environmentProbe)) {
            is PortResult.Unavailable -> {
                StopStep.Outcome(RehearsalStopOutcome.Retryable(observed.failure.message))
            }

            is PortResult.Observed -> {
                val snapshotEnvironment = checkNotNull(report.environmentSnapshot).cameraEnvironment
                if (observed.value == snapshotEnvironment && observed.value == profile.environment) {
                    StopStep.Ready(Unit)
                } else {
                    StopStep.Outcome(
                        RehearsalStopOutcome.Retryable("Pixel Camera environment changed before profile promotion"),
                    )
                }
            }
        }

    private suspend fun persistVerifiedProfile(
        report: ExecutionReport,
        profile: PixelCameraProfile,
    ): RehearsalStopOutcome {
        val verified =
            profile.copy(
                compatibility = ProfileCompatibility.VERIFIED,
                verifiedAt = checkNotNull(report.session.mediaSavedVerifiedAt),
            )
        val persisted =
            preservingCancellation {
                profileRepository.save(verified)
                profileRepository.get(verified.id)
            }
        return when {
            persisted.isFailure -> {
                RehearsalStopOutcome.Retryable("Could not persist verified profile")
            }

            persisted.getOrNull() != verified -> {
                RehearsalStopOutcome.Retryable("Verified profile read-back did not match")
            }

            backstop.cancel(report.session.id).isFailure -> {
                RehearsalStopOutcome.Retryable(
                    "Verified profile persisted but STOP backstop cancellation failed",
                )
            }

            else -> {
                RehearsalStopOutcome.Promoted(report.session, verified)
            }
        }
    }
}

private sealed interface StopStep<out T> {
    data class Ready<T>(
        val value: T,
    ) : StopStep<T>

    data class Outcome(
        val value: RehearsalStopOutcome,
    ) : StopStep<Nothing>
}

private val ExecutionSession.hasCompleteStopProof: Boolean
    get() = stoppedVerifiedAt != null && mediaSavedVerifiedAt != null

private suspend fun cancelSafelyStopped(
    backstop: RehearsalStopBackstop,
    session: ExecutionSession,
    message: String = "Rehearsal did not qualify for promotion but STOP was verified",
): RehearsalStopOutcome =
    if (backstop.cancel(session.id).isSuccess) {
        RehearsalStopOutcome.SafeFailure(session, message)
    } else {
        RehearsalStopOutcome.Retryable("STOP was verified but backstop cancellation failed")
    }

private suspend inline fun <T> preservingCancellation(crossinline block: suspend () -> T): Result<T> =
    runCatching { block() }.onFailure { failure ->
        if (failure is CancellationException || failure !is Exception) throw failure
    }

private val TERMINAL_STOP_STATUSES = setOf(SessionStatus.FAILED, SessionStatus.CANCELLED)

private object RehearsalSupport {
    fun rejected(
        code: RehearsalResultCode,
        message: String,
        sessionId: SessionId? = null,
    ) = RehearsalResult.Rejected(code, message, sessionId)

    fun deterministicSnapshotId(sessionId: SessionId): EnvironmentSnapshotId =
        EnvironmentSnapshotId(
            UUID
                .nameUUIDFromBytes(
                    "environment/${sessionId.value}".toByteArray(StandardCharsets.UTF_8),
                ).toString(),
        )

    fun inspectEnvironment(
        environmentProbe: () -> PortResult<PixelCameraEnvironment>,
    ): PortResult<PixelCameraEnvironment> {
        val attempt = runCatching(environmentProbe)
        val failure = attempt.exceptionOrNull()
        return when {
            failure == null -> {
                checkNotNull(attempt.getOrNull())
            }

            failure is CancellationException -> {
                throw failure
            }

            failure !is Exception -> {
                throw failure
            }

            else -> {
                PortResult.Unavailable(
                    AutomationFailure(AutomationFailureCode.UNKNOWN, "Pixel Camera environment inspection failed"),
                )
            }
        }
    }

    fun validatePromotionProof(report: ExecutionReport): String? {
        val session = report.session
        val snapshot = report.environmentSnapshot
        return when {
            session.kind != SessionKind.REHEARSAL -> {
                "Execution is not a rehearsal"
            }

            session.status != SessionStatus.COMPLETED -> {
                "Rehearsal is not completed"
            }

            session.failure != null -> {
                "Rehearsal completed with a failure"
            }

            snapshot == null -> {
                "Rehearsal environment snapshot is missing"
            }

            snapshot.sessionId != session.id || snapshot.id != session.environmentSnapshotId -> {
                "Rehearsal environment snapshot linkage is invalid"
            }

            session.recordActionAt == null -> {
                "Rehearsal record dispatch proof is missing"
            }

            session.recordingVerifiedAt == null -> {
                "Rehearsal recording verification is missing"
            }

            session.stopActionAt == null -> {
                "Rehearsal stop dispatch proof is missing"
            }

            session.stoppedVerifiedAt == null -> {
                "Rehearsal stop verification is missing"
            }

            session.mediaSavedVerifiedAt == null -> {
                "Rehearsal saved-media verification is missing"
            }

            else -> {
                null
            }
        }
    }

    fun ownershipReleaseFailure(cancelled: Boolean): AutomationFailure =
        if (cancelled) {
            AutomationFailure(
                AutomationFailureCode.AUTOMATION_CANCELLED,
                "Rehearsal was cancelled before recording ownership was acquired",
            )
        } else {
            AutomationFailure(
                AutomationFailureCode.AUTOMATION_TIMEOUT,
                "Rehearsal reached its STOP deadline before recording ownership was acquired",
            )
        }
}

sealed interface RehearsalStopOutcome {
    data class Promoted(
        val session: ExecutionSession,
        val profile: PixelCameraProfile,
    ) : RehearsalStopOutcome

    data class SafeFailure(
        val session: ExecutionSession,
        val message: String,
    ) : RehearsalStopOutcome

    data class Retryable(
        val message: String,
    ) : RehearsalStopOutcome

    data class Invalid(
        val message: String,
    ) : RehearsalStopOutcome
}

private fun AutomationRunResult.sessionOrNull(): ExecutionSession? =
    when (this) {
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

private fun AutomationRunResult.failureMessage(): String =
    when (this) {
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

internal fun ExecutionSession.testedProfileFingerprint(): String? =
    executionKey
        .substringAfterLast('/', missingDelimiterValue = "")
        .takeIf { it.length == SHA_256_HEX_LENGTH && it.all(Char::isHexDigit) }

internal fun PixelCameraProfile.definitionFingerprint(): String {
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
    return MessageDigest
        .getInstance("SHA-256")
        .digest(bytes.toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }
}

internal fun ExecutionSession.qualifiesRehearsal(
    profile: PixelCameraProfile,
    capture: CaptureConfiguration,
): Boolean =
    kind == SessionKind.REHEARSAL &&
        status == SessionStatus.COMPLETED &&
        profileId == profile.id &&
        this.capture == capture &&
        recordingVerifiedAt != null &&
        stopActionAt != null &&
        stoppedVerifiedAt != null &&
        mediaSavedVerifiedAt != null &&
        testedProfileFingerprint() == profile.definitionFingerprint()

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

private const val SHA_256_HEX_LENGTH = 64
private const val UNSIGNED_BYTE_MASK = 0xFF
