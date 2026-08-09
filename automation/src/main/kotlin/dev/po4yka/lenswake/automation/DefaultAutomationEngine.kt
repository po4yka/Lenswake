package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

interface AutomationEngine {
    suspend fun start(sessionId: SessionId): AutomationRunResult

    suspend fun stop(sessionId: SessionId): AutomationRunResult
}

sealed interface AutomationRunResult {
    data class Succeeded(
        val session: ExecutionSession,
    ) : AutomationRunResult

    data class AlreadySatisfied(
        val session: ExecutionSession,
    ) : AutomationRunResult

    data class AlreadyTerminal(
        val session: ExecutionSession,
    ) : AutomationRunResult

    data class StopVerifiedAfterFailure(
        val session: ExecutionSession,
        val originalFailure: AutomationFailure?,
    ) : AutomationRunResult

    data class NotFound(
        val sessionId: SessionId,
    ) : AutomationRunResult

    data class Rejected(
        val session: ExecutionSession,
        val failure: AutomationFailure,
    ) : AutomationRunResult

    data class Failed(
        val session: ExecutionSession,
        val failure: AutomationFailure,
    ) : AutomationRunResult

    data class RevisionConflict(
        val session: ExecutionSession,
        val expectedRevision: Long,
        val actualRevision: Long?,
    ) : AutomationRunResult

    data class PersistenceFailure(
        val session: ExecutionSession?,
        val failure: AutomationFailure,
    ) : AutomationRunResult
}

class DefaultAutomationEngine(
    private val executionRepository: ExecutionRepository,
    private val profileRepository: AutomationProfileRepository,
    private val deviceControl: DeviceControlPort,
    private val pixelCamera: PixelCameraPort,
    private val clock: LenswakeClock,
    private val config: AutomationConfig = AutomationConfig.production(),
    private val sleeper: AutomationSleeper = CoroutineAutomationSleeper,
) : AutomationEngine {
    override suspend fun start(sessionId: SessionId): AutomationRunResult = execute(sessionId) { context ->
        val uncertainDispatchAtEntry = context.current.hasUncertainRecordDispatch()
        when (context.current.status) {
            SessionStatus.RECORDING -> return@execute if (
                context.current.recordActionAt != null && context.current.recordingVerifiedAt != null
            ) {
                AutomationRunResult.AlreadySatisfied(context.current)
            } else {
                rejectedState(context.current, "Recording state has no Lenswake ownership evidence")
            }
            SessionStatus.COMPLETED,
            SessionStatus.CANCELLED,
            -> return@execute AutomationRunResult.AlreadyTerminal(context.current)
            SessionStatus.FAILED -> if (!uncertainDispatchAtEntry) {
                return@execute AutomationRunResult.AlreadyTerminal(context.current)
            }
            SessionStatus.STOPPING -> return@execute rejectedState(context.current, "Cannot start a session while it is stopping")
            SessionStatus.PENDING,
            SessionStatus.STARTING,
            -> Unit
        }

        validateSupportedCapture(context)
        context.profile = loadProfile(context)

        context.transition(
            state = AutomationStateName.START_TRIGGERED,
            status = SessionStatus.STARTING,
            outcome = AutomationOutcome.STARTED,
        )
        context.transition(
            state = AutomationStateName.VALIDATING_SESSION,
            status = SessionStatus.STARTING,
            outcome = AutomationOutcome.SUCCEEDED,
        )

        ensureInteractive(context, AutomationStateName.WAKING_DEVICE)
        launchAndObserveCamera(context)
        if (uncertainDispatchAtEntry) {
            reconcileUncertainStart(context)
        } else {
            convergeStart(context)
        }
    }

    override suspend fun stop(sessionId: SessionId): AutomationRunResult = execute(sessionId) { context ->
        val originalStatus = context.current.status
        val originalFailure = context.current.failure
        val hasOwnership = context.current.recordActionAt != null
        val stopOutstanding = context.current.stoppedVerifiedAt == null
        when (context.current.status) {
            SessionStatus.COMPLETED,
            SessionStatus.CANCELLED,
            -> return@execute AutomationRunResult.AlreadyTerminal(context.current)
            SessionStatus.FAILED -> if (!hasOwnership || !stopOutstanding) {
                return@execute AutomationRunResult.AlreadyTerminal(context.current)
            }
            SessionStatus.PENDING,
            SessionStatus.STARTING,
            SessionStatus.RECORDING,
            SessionStatus.STOPPING,
            -> Unit
        }
        if (!hasOwnership) {
            return@execute rejectedState(
                context.current,
                "Cannot stop a recording that Lenswake did not dispatch",
            )
        }
        if (!stopOutstanding) return@execute AutomationRunResult.AlreadyTerminal(context.current)

        val preserveFailedOutcome =
            originalStatus == SessionStatus.FAILED || context.current.recordingVerifiedAt == null
        val preservedFailure = originalFailure ?: if (preserveFailedOutcome) {
            failure(
                AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                "A dispatched recording was never verified before safe stop recovery",
            )
        } else {
            null
        }

        context.profile = loadProfile(context)

        context.transition(
            state = AutomationStateName.STOP_TRIGGERED,
            status = SessionStatus.STOPPING,
            outcome = AutomationOutcome.STARTED,
        )
        context.transition(
            state = AutomationStateName.VALIDATING_ACTIVE_SESSION,
            status = SessionStatus.STOPPING,
            outcome = AutomationOutcome.SUCCEEDED,
        )
        context.transition(
            state = AutomationStateName.INSPECTING_DEVICE,
            status = SessionStatus.STOPPING,
            outcome = AutomationOutcome.STARTED,
        )
        ensureInteractive(context, AutomationStateName.WAKING_IF_REQUIRED)

        var beforeStop = observeCamera(
            context = context,
            operation = AutomationOperation.INSPECT_CAMERA,
            state = AutomationStateName.INSPECTING_RECORDING_STATE,
            failureCode = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
            failureMessage = "Pixel Camera state could not be inspected before stop",
        ) { it !is PixelCameraState.Unknown }
        if (beforeStop is PixelCameraState.NotRunning) {
            beforeStop = launchAndObserveCamera(context, AutomationStateName.LOCATING_PIXEL_CAMERA)
        }

        val capture = context.current.capture as CaptureConfiguration.TimeLapse
        if (beforeStop.isConfirmedRecording(capture)) {
            dispatch(
                context = context,
                operation = AutomationOperation.STOP_RECORDING,
                state = AutomationStateName.STOPPING_RECORDING,
                defaultFailureCode = AutomationFailureCode.STOP_ACTION_FAILED,
                defaultFailureMessage = "Pixel Camera rejected the stop action",
                action = { pixelCamera.stopRecording(context.profile) },
            ) { session, now -> session.copy(stopActionAt = now) }
        } else if (!beforeStop.isConfirmedStopped()) {
            fail(
                context,
                failure(
                    AutomationFailureCode.STOP_NOT_CONFIRMED,
                    "The active recording could not be identified safely",
                ),
            )
        }

        observeCamera(
            context = context,
            operation = AutomationOperation.VERIFY_STOPPED,
            state = AutomationStateName.VERIFYING_STOPPED,
            failureCode = AutomationFailureCode.STOP_NOT_CONFIRMED,
            failureMessage = "Pixel Camera did not leave the recording state",
            predicate = { it.isConfirmedStopped() },
        )
        if (preserveFailedOutcome) {
            context.transition(
                state = AutomationStateName.FAILED,
                status = SessionStatus.FAILED,
                operation = AutomationOperation.VERIFY_STOPPED,
                outcome = AutomationOutcome.SUCCEEDED,
                metadata = mapOf("recovery" to "dispatched_but_unverified_recording"),
            ) { session, now -> session.copy(stoppedVerifiedAt = now, failure = preservedFailure) }
            AutomationRunResult.StopVerifiedAfterFailure(context.current, preservedFailure)
        } else {
            context.transition(
                state = AutomationStateName.COMPLETED,
                status = SessionStatus.COMPLETED,
                operation = AutomationOperation.VERIFY_STOPPED,
                outcome = AutomationOutcome.SUCCEEDED,
            ) { session, now -> session.copy(stoppedVerifiedAt = now, failure = null) }
            AutomationRunResult.Succeeded(context.current)
        }
    }

    private suspend fun convergeStart(context: RunContext): AutomationRunResult {
        val capture = context.current.capture as CaptureConfiguration.TimeLapse
        repeat(config.maxConvergenceSteps) {
            val state = observeCamera(
                context = context,
                operation = AutomationOperation.INSPECT_CAMERA,
                state = AutomationStateName.INSPECTING_CAMERA_STATE,
                failureCode = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                failureMessage = "Pixel Camera state could not be inspected",
            ) { it !is PixelCameraState.NotRunning && it !is PixelCameraState.Unknown }

            when (state) {
                PixelCameraState.Photo -> {
                    dispatchAndVerify(
                        context = context,
                        operation = AutomationOperation.SELECT_VIDEO,
                        actionState = AutomationStateName.SELECTING_VIDEO,
                        verificationState = AutomationStateName.VERIFYING_VIDEO,
                        dispatchFailure = failure(
                            AutomationFailureCode.VIDEO_MODE_NOT_FOUND,
                            "Pixel Camera could not select Video mode",
                        ),
                        verificationFailure = failure(
                            AutomationFailureCode.VIDEO_MODE_NOT_VERIFIED,
                            "Pixel Camera did not confirm Video mode",
                        ),
                        action = { pixelCamera.selectVideo(context.profile) },
                    ) { it is PixelCameraState.Video && !it.recording }
                }

                is PixelCameraState.Video -> {
                    if (state.recording) {
                        fail(
                            context,
                            failure(
                                AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                                "Pixel Camera is recording in a mode that Lenswake must not alter",
                            ),
                        )
                    }
                    dispatchAndVerify(
                        context = context,
                        operation = AutomationOperation.SELECT_TIME_LAPSE,
                        actionState = AutomationStateName.SELECTING_TIME_LAPSE,
                        verificationState = AutomationStateName.VERIFYING_TIME_LAPSE,
                        dispatchFailure = failure(
                            AutomationFailureCode.TIME_LAPSE_MODE_NOT_FOUND,
                            "Pixel Camera could not select Time Lapse mode",
                        ),
                        verificationFailure = failure(
                            AutomationFailureCode.TIME_LAPSE_MODE_NOT_VERIFIED,
                            "Pixel Camera did not confirm Time Lapse mode",
                        ),
                        action = { pixelCamera.selectTimeLapse(context.profile) },
                    ) { it is PixelCameraState.TimeLapse && !it.recording }
                }

                is PixelCameraState.TimeLapse -> when {
                    state.recording &&
                        state.speed == capture.speed &&
                        state.lens == LensSelection.REAR_MAIN &&
                        context.current.recordActionAt != null -> {
                        return markRecordingVerified(context)
                    }

                    state.recording &&
                        state.speed == capture.speed &&
                        state.lens == LensSelection.REAR_MAIN -> fail(
                        context,
                        failure(
                            AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                            "Refusing to claim a recording Lenswake did not dispatch",
                        ),
                    )

                    state.recording -> fail(
                        context,
                        failure(
                            AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                            "Pixel Camera is already recording with a different Time Lapse speed",
                        ),
                    )

                    state.lens != LensSelection.REAR_MAIN -> dispatchAndVerify(
                        context = context,
                        operation = AutomationOperation.SELECT_REAR_MAIN_LENS,
                        actionState = AutomationStateName.SELECTING_REAR_MAIN_LENS,
                        verificationState = AutomationStateName.VERIFYING_REAR_MAIN_LENS,
                        dispatchFailure = failure(
                            AutomationFailureCode.LENS_NOT_FOUND,
                            "Pixel Camera could not select the rear main lens",
                        ),
                        verificationFailure = failure(
                            AutomationFailureCode.LENS_NOT_VERIFIED,
                            "Pixel Camera did not confirm the rear main lens",
                        ),
                        action = { pixelCamera.selectRearMainLens(context.profile) },
                    ) {
                        it is PixelCameraState.TimeLapse &&
                            !it.recording &&
                            it.lens == LensSelection.REAR_MAIN
                    }

                    state.speed != capture.speed -> dispatchAndVerify(
                        context = context,
                        operation = AutomationOperation.SELECT_TIME_LAPSE_SPEED,
                        actionState = AutomationStateName.SELECTING_SPEED,
                        verificationState = AutomationStateName.VERIFYING_SPEED,
                        dispatchFailure = failure(
                            AutomationFailureCode.TIME_LAPSE_SPEED_NOT_FOUND,
                            "Pixel Camera could not select the requested Time Lapse speed",
                        ),
                        verificationFailure = failure(
                            AutomationFailureCode.TIME_LAPSE_SPEED_NOT_VERIFIED,
                            "Pixel Camera did not confirm the requested Time Lapse speed",
                        ),
                        action = { pixelCamera.selectTimeLapseSpeed(capture.speed, context.profile) },
                    ) { it is PixelCameraState.TimeLapse && !it.recording && it.speed == capture.speed }

                    else -> {
                        if (context.current.hasUncertainRecordDispatch()) {
                            fail(
                                context,
                                failure(
                                    AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                                    "An uncertain prior Record dispatch must be reconciled without redispatch",
                                ),
                            )
                        }
                        dispatchRecordingStart(context)
                        observeCamera(
                            context = context,
                            operation = AutomationOperation.VERIFY_RECORDING,
                            state = AutomationStateName.VERIFYING_RECORDING,
                            failureCode = AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                            failureMessage = "Pixel Camera did not confirm Time Lapse recording",
                        ) { observed ->
                            observed is PixelCameraState.TimeLapse &&
                                observed.recording &&
                                observed.speed == capture.speed &&
                                observed.lens == LensSelection.REAR_MAIN
                        }
                        return markRecordingVerified(context)
                    }
                }

                PixelCameraState.RecordingUnknownMode -> fail(
                    context,
                    failure(
                        AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                        "Pixel Camera is recording in an unknown mode and will not be altered",
                    ),
                )

                PixelCameraState.NotRunning,
                PixelCameraState.Unknown,
                -> fail(
                    context,
                    failure(AutomationFailureCode.CAMERA_STATE_UNKNOWN, "Pixel Camera state is unknown"),
                )
            }
        }
        fail(
            context,
            failure(
                AutomationFailureCode.AUTOMATION_TIMEOUT,
                "Pixel Camera did not converge within ${config.maxConvergenceSteps} semantic transitions",
            ),
        )
    }

    private suspend fun reconcileUncertainStart(context: RunContext): AutomationRunResult {
        val capture = context.current.capture as CaptureConfiguration.TimeLapse
        val observed = observeCamera(
            context = context,
            operation = AutomationOperation.VERIFY_RECORDING,
            state = AutomationStateName.VERIFYING_RECORDING,
            failureCode = AutomationFailureCode.RECORDING_NOT_CONFIRMED,
            failureMessage = "An uncertain Record dispatch could not be observed",
        ) { it !is PixelCameraState.NotRunning && it !is PixelCameraState.Unknown }
        return if (
            observed is PixelCameraState.TimeLapse &&
            observed.recording &&
            observed.speed == capture.speed &&
            observed.lens == LensSelection.REAR_MAIN
        ) {
            markRecordingVerified(context)
        } else {
            fail(
                context,
                failure(
                    AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                    "Uncertain Record dispatch was not confirmed; checkpoint retained for STOP reconciliation",
                ),
            )
        }
    }

    private suspend fun markRecordingVerified(context: RunContext): AutomationRunResult.Succeeded {
        context.transition(
            state = AutomationStateName.VERIFYING_RECORDING,
            status = SessionStatus.STARTING,
            operation = AutomationOperation.VERIFY_RECORDING,
            outcome = AutomationOutcome.SUCCEEDED,
        )
        context.transition(
            state = AutomationStateName.RECORDING,
            status = SessionStatus.RECORDING,
            operation = AutomationOperation.VERIFY_RECORDING,
            outcome = AutomationOutcome.SUCCEEDED,
        ) { session, now -> session.copy(recordingVerifiedAt = now, failure = null) }
        return AutomationRunResult.Succeeded(context.current)
    }

    private suspend fun ensureInteractive(
        context: RunContext,
        state: AutomationStateName,
    ) {
        context.transition(
            state = state,
            operation = AutomationOperation.WAKE_DEVICE,
            outcome = AutomationOutcome.STARTED,
        )
        val initial = inspectDevice()
        if (initial is PortResult.Observed && initial.value.interactive) return

        dispatch(
            context = context,
            operation = AutomationOperation.WAKE_DEVICE,
            state = state,
            defaultFailureCode = AutomationFailureCode.WAKE_FAILED,
            defaultFailureMessage = "The device rejected the wake action",
            action = deviceControl::wake,
        )

        val policy = config.policyFor(AutomationOperation.WAKE_DEVICE)
        var lastFailure: AutomationFailure? = (initial as? PortResult.Unavailable)?.failure
        for (attempt in 1..policy.maxAttempts) {
            if (attempt > 1) {
                retryTransition(context, AutomationOperation.WAKE_DEVICE, attempt, state)
                sleeper.sleep(policy.delayBeforeAttempt(attempt))
            }
            when (val observed = inspectDevice()) {
                is PortResult.Observed -> if (observed.value.interactive) return
                is PortResult.Unavailable -> lastFailure = observed.failure
            }
        }
        fail(
            context,
            lastFailure ?: failure(AutomationFailureCode.WAKE_FAILED, "The device did not become interactive"),
        )
    }

    private suspend fun launchAndObserveCamera(
        context: RunContext,
        launchState: AutomationStateName = AutomationStateName.LAUNCHING_SECURE_CAMERA,
    ): PixelCameraState {
        dispatch(
            context = context,
            operation = AutomationOperation.LAUNCH_CAMERA,
            state = launchState,
            defaultFailureCode = AutomationFailureCode.PIXEL_CAMERA_LAUNCH_FAILED,
            defaultFailureMessage = "Secure Pixel Camera launch was rejected",
            action = { pixelCamera.launchSecureCamera(context.profile) },
        )
        return observeCamera(
            context = context,
            operation = AutomationOperation.INSPECT_CAMERA,
            state = if (launchState == AutomationStateName.LOCATING_PIXEL_CAMERA) {
                AutomationStateName.LOCATING_PIXEL_CAMERA
            } else {
                AutomationStateName.WAITING_FOR_PIXEL_CAMERA
            },
            failureCode = AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND,
            failureMessage = "Pixel Camera did not become observable after launch",
        ) { it !is PixelCameraState.NotRunning && it !is PixelCameraState.Unknown }
    }

    private suspend fun dispatchAndVerify(
        context: RunContext,
        operation: AutomationOperation,
        actionState: AutomationStateName,
        verificationState: AutomationStateName,
        dispatchFailure: AutomationFailure,
        verificationFailure: AutomationFailure,
        action: suspend () -> ActionDispatch,
        predicate: (PixelCameraState) -> Boolean,
    ) {
        dispatch(
            context = context,
            operation = operation,
            state = actionState,
            defaultFailureCode = dispatchFailure.code,
            defaultFailureMessage = dispatchFailure.message,
            action = action,
        )
        observeCamera(
            context = context,
            operation = operation,
            state = verificationState,
            failureCode = verificationFailure.code,
            failureMessage = verificationFailure.message,
            predicate = predicate,
        )
    }

    private suspend fun dispatchRecordingStart(context: RunContext) {
        val operation = AutomationOperation.START_RECORDING
        val state = AutomationStateName.STARTING_RECORDING
        val policy = config.policyFor(operation)
        var lastRejection: AutomationFailure? = null

        for (attempt in 1..policy.maxAttempts) {
            if (attempt > 1) {
                retryTransition(context, operation, attempt, state)
                sleeper.sleep(policy.delayBeforeAttempt(attempt))
            }

            context.transition(
                state = state,
                operation = operation,
                outcome = AutomationOutcome.STARTED,
                attempt = attempt,
                metadata = mapOf("dispatchCheckpoint" to "write_ahead"),
            ) { session, now -> session.copy(recordActionAt = session.recordActionAt ?: now) }

            val invocation = try {
                timed(operation) { pixelCamera.startRecording(context.profile) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(
                    context,
                    operationFailure(
                        AutomationFailureCode.RECORD_ACTION_FAILED,
                        "Record dispatch threw after a possible external side effect",
                        error,
                    ),
                )
            }

            when (invocation) {
                TimedCall.TimedOut -> fail(context, timeoutFailure(operation))
                is TimedCall.Completed -> when (val dispatch = invocation.value) {
                    is ActionDispatch.Dispatched -> {
                        context.transition(
                            state = state,
                            operation = operation,
                            outcome = AutomationOutcome.DISPATCHED,
                            method = dispatch.method,
                            attempt = attempt,
                        )
                        return
                    }

                    is ActionDispatch.Rejected -> {
                        lastRejection = dispatch.failure
                        context.transition(
                            state = state,
                            operation = operation,
                            outcome = AutomationOutcome.FAILED,
                            attempt = attempt,
                            failure = dispatch.failure,
                            metadata = mapOf("dispatchCheckpoint" to "cleared_definitive_rejection"),
                        ) { session, _ -> session.copy(recordActionAt = null) }
                    }
                }
            }
        }

        fail(
            context,
            lastRejection ?: failure(
                AutomationFailureCode.RECORD_ACTION_FAILED,
                "Pixel Camera definitively rejected the Record action",
            ),
        )
    }

    private suspend fun dispatch(
        context: RunContext,
        operation: AutomationOperation,
        state: AutomationStateName,
        defaultFailureCode: AutomationFailureCode,
        defaultFailureMessage: String,
        action: suspend () -> ActionDispatch,
        onDispatched: (ExecutionSession, Instant) -> ExecutionSession = { session, _ -> session },
    ): InteractionMethod {
        val policy = config.policyFor(operation)
        var lastFailure: AutomationFailure? = null
        for (attempt in 1..policy.maxAttempts) {
            if (attempt > 1) {
                retryTransition(context, operation, attempt, state)
                sleeper.sleep(policy.delayBeforeAttempt(attempt))
            }
            context.transition(
                state = state,
                operation = operation,
                outcome = AutomationOutcome.STARTED,
                attempt = attempt,
            )
            val result = try {
                when (val timed = timed(operation, action)) {
                    is TimedCall.Completed -> timed.value
                    TimedCall.TimedOut -> ActionDispatch.Rejected(timeoutFailure(operation))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ActionDispatch.Rejected(
                    operationFailure(defaultFailureCode, defaultFailureMessage, error),
                )
            }
            when (result) {
                is ActionDispatch.Dispatched -> {
                    context.transition(
                        state = state,
                        operation = operation,
                        outcome = AutomationOutcome.DISPATCHED,
                        method = result.method,
                        attempt = attempt,
                        update = onDispatched,
                    )
                    return result.method
                }

                is ActionDispatch.Rejected -> lastFailure = result.failure
            }
        }
        fail(
            context,
            lastFailure ?: failure(defaultFailureCode, defaultFailureMessage),
        )
    }

    private suspend fun observeCamera(
        context: RunContext,
        operation: AutomationOperation,
        state: AutomationStateName,
        failureCode: AutomationFailureCode,
        failureMessage: String,
        predicate: (PixelCameraState) -> Boolean,
    ): PixelCameraState {
        val policy = config.policyFor(operation)
        var lastFailure: AutomationFailure? = null
        for (attempt in 1..policy.maxAttempts) {
            if (attempt > 1) {
                retryTransition(context, operation, attempt, state)
                sleeper.sleep(policy.delayBeforeAttempt(attempt))
            }
            context.transition(
                state = state,
                operation = operation,
                outcome = AutomationOutcome.STARTED,
                attempt = attempt,
            )
            val inspection = try {
                when (val timed = timed(operation) { pixelCamera.inspect(context.profile) }) {
                    is TimedCall.Completed -> timed.value
                    TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                PortResult.Unavailable(operationFailure(failureCode, failureMessage, error))
            }
            when (val observed = inspection) {
                is PortResult.Observed -> if (predicate(observed.value)) {
                    context.transition(
                        state = state,
                        operation = operation,
                        outcome = AutomationOutcome.SUCCEEDED,
                        attempt = attempt,
                    )
                    return observed.value
                }

                is PortResult.Unavailable -> lastFailure = observed.failure
            }
        }
        fail(context, lastFailure ?: failure(failureCode, failureMessage))
    }

    private suspend fun retryTransition(
        context: RunContext,
        operation: AutomationOperation,
        attempt: Int,
        returnState: AutomationStateName,
    ) {
        context.transition(
            state = AutomationStateName.RETRYING,
            operation = operation,
            outcome = AutomationOutcome.RETRYING,
            attempt = attempt,
            metadata = mapOf("returnState" to returnState.name),
        )
    }

    private suspend fun fail(
        context: RunContext,
        failure: AutomationFailure,
    ): Nothing {
        context.transition(
            state = AutomationStateName.FAILED,
            status = SessionStatus.FAILED,
            outcome = AutomationOutcome.FAILED,
            failure = failure,
        ) { session, _ -> session.copy(failure = failure) }
        throw EngineAbort(AutomationRunResult.Failed(context.current, failure))
    }

    private suspend fun execute(
        sessionId: SessionId,
        workflow: suspend (RunContext) -> AutomationRunResult,
    ): AutomationRunResult {
        val session = try {
            executionRepository.get(sessionId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return AutomationRunResult.PersistenceFailure(
                session = null,
                failure = persistenceFailure("Could not load execution session", error),
            )
        } ?: return AutomationRunResult.NotFound(sessionId)

        return try {
            workflow(RunContext(session))
        } catch (abort: EngineAbort) {
            abort.result
        }
    }

    private inner class RunContext(
        var current: ExecutionSession,
    ) {
        lateinit var profile: PixelCameraProfile

        suspend fun transition(
            state: AutomationStateName,
            status: SessionStatus = current.status,
            operation: AutomationOperation? = null,
            outcome: AutomationOutcome,
            method: InteractionMethod? = null,
            attempt: Int? = null,
            failure: AutomationFailure? = null,
            metadata: Map<String, String> = emptyMap(),
            update: (ExecutionSession, Instant) -> ExecutionSession = { session, _ -> session },
        ) {
            if (current.revision == Long.MAX_VALUE) {
                throw EngineAbort(
                    AutomationRunResult.PersistenceFailure(
                        current,
                        failure(
                            AutomationFailureCode.SESSION_PERSISTENCE_FAILED,
                            "Execution revision cannot be incremented",
                        ),
                    ),
                )
            }
            val now = maxOf(clock.now(), current.updatedAt)
            val nextRevision = current.revision + 1
            val base = current.copy(
                status = status,
                currentAutomationState = state,
                revision = nextRevision,
                updatedAt = now,
            )
            val updated = update(base, now).copy(revision = nextRevision, updatedAt = now)
            val event = AutomationEvent(
                id = EventId.new(),
                sessionId = current.id,
                name = eventName(state, operation, outcome),
                sequence = nextRevision,
                timestamp = now,
                state = state,
                operation = operation,
                outcome = outcome,
                interactionMethod = method,
                attempt = attempt,
                failure = failure,
                metadata = metadata,
            )
            val result = try {
                executionRepository.apply(
                    change = ExecutionChange(current.revision, updated),
                    event = event,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw EngineAbort(
                    AutomationRunResult.PersistenceFailure(
                        current,
                        persistenceFailure("Could not persist automation transition $state", error),
                    ),
                )
            }
            when (result) {
                is ExecutionApplyResult.Applied -> current = result.session
                is ExecutionApplyResult.RevisionConflict -> throw EngineAbort(
                    AutomationRunResult.RevisionConflict(
                        session = current,
                        expectedRevision = result.expectedRevision,
                        actualRevision = result.actualRevision,
                    ),
                )
            }
        }
    }

    private fun rejectedState(session: ExecutionSession, message: String): AutomationRunResult.Rejected =
        AutomationRunResult.Rejected(
            session,
            failure(AutomationFailureCode.SESSION_STATE_CONFLICT, message),
        )

    private suspend fun loadProfile(context: RunContext): PixelCameraProfile {
        val profile = try {
            profileRepository.get(context.current.profileId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw EngineAbort(
                AutomationRunResult.PersistenceFailure(
                    context.current,
                    persistenceFailure("Could not load Pixel Camera profile", error),
                ),
            )
        } ?: profileFailure(
            context,
            failure(
                AutomationFailureCode.PROFILE_NOT_FOUND,
                "Pixel Camera profile ${context.current.profileId.value} was not found",
            ),
        )

        when (profile.compatibility) {
            ProfileCompatibility.INCOMPATIBLE -> profileFailure(
                context,
                failure(
                    AutomationFailureCode.PROFILE_INCOMPATIBLE,
                    "Pixel Camera profile is marked incompatible",
                ),
            )

            ProfileCompatibility.NEEDS_REHEARSAL,
            ProfileCompatibility.PROBABLY_COMPATIBLE,
            -> if (context.current.kind == SessionKind.SCHEDULED) {
                profileFailure(
                    context,
                    failure(
                        AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL,
                        "Unattended execution requires a verified Pixel Camera profile",
                    ),
                )
            }

            ProfileCompatibility.VERIFIED -> Unit
        }
        return profile
    }

    private suspend fun profileFailure(
        context: RunContext,
        failure: AutomationFailure,
    ): Nothing {
        if (context.current.status == SessionStatus.FAILED) {
            throw EngineAbort(AutomationRunResult.Rejected(context.current, failure))
        }
        fail(context, failure)
    }

    private fun persistenceFailure(message: String, error: Exception): AutomationFailure = failure(
        AutomationFailureCode.SESSION_PERSISTENCE_FAILED,
        message,
        mapOf("exception" to (error::class.qualifiedName ?: error::class.simpleName.orEmpty()).take(256)),
    )

    private suspend fun inspectDevice(): PortResult<DeviceState> = try {
        when (val timed = timed(AutomationOperation.WAKE_DEVICE, deviceControl::inspect)) {
            is TimedCall.Completed -> timed.value
            TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(AutomationOperation.WAKE_DEVICE))
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        PortResult.Unavailable(
            operationFailure(AutomationFailureCode.WAKE_FAILED, "The device state could not be inspected", error),
        )
    }

    private fun operationFailure(
        code: AutomationFailureCode,
        message: String,
        error: Exception,
    ): AutomationFailure = failure(
        code,
        message,
        mapOf("exception" to (error::class.qualifiedName ?: error::class.simpleName.orEmpty()).take(256)),
    )

    private suspend fun <T> timed(
        operation: AutomationOperation,
        block: suspend () -> T,
    ): TimedCall<T> = withTimeoutOrNull(config.timeoutFor(operation)) {
        TimedCall.Completed(block())
    } ?: TimedCall.TimedOut

    private fun timeoutFailure(operation: AutomationOperation): AutomationFailure = failure(
        AutomationFailureCode.AUTOMATION_TIMEOUT,
        "Automation operation $operation exceeded its finite timeout",
        mapOf(
            "operation" to operation.name,
            "timeoutMs" to config.timeoutFor(operation).inWholeMilliseconds.toString(),
        ),
    )

    private suspend fun validateSupportedCapture(context: RunContext) {
        val capture = context.current.capture as CaptureConfiguration.TimeLapse
        if (capture.lens != LensSelection.REAR_MAIN || capture.zoom != null) {
            fail(
                context,
                failure(
                    AutomationFailureCode.UNSUPPORTED_CAPTURE_CONFIGURATION,
                    "Baseline automation supports only the rear main lens without zoom",
                    mapOf(
                        "lens" to capture.lens.name,
                        "zoom" to (capture.zoom?.factor?.toString() ?: "none"),
                    ),
                ),
            )
        }
    }

    private fun failure(
        code: AutomationFailureCode,
        message: String,
        context: Map<String, String> = emptyMap(),
    ) = AutomationFailure(code, message, context)

    private fun eventName(
        state: AutomationStateName,
        operation: AutomationOperation?,
        outcome: AutomationOutcome,
    ): String = when {
        state == AutomationStateName.RECORDING -> "automation.record.start_verified"
        state == AutomationStateName.COMPLETED -> "automation.record.stop_verified"
        state == AutomationStateName.FAILED &&
            operation == AutomationOperation.VERIFY_STOPPED &&
            outcome == AutomationOutcome.SUCCEEDED -> "automation.record.stop_verified_after_failure"
        state == AutomationStateName.FAILED -> "automation.failed"
        outcome == AutomationOutcome.DISPATCHED && operation != null ->
            "automation.${operation.name.lowercase()}.dispatched"
        else -> "automation.state.${state.name.lowercase()}"
    }

    private fun PixelCameraState.isConfirmedRecording(capture: CaptureConfiguration.TimeLapse): Boolean =
        this is PixelCameraState.TimeLapse &&
            recording &&
            speed == capture.speed &&
            lens == LensSelection.REAR_MAIN

    private fun PixelCameraState.isConfirmedStopped(): Boolean = when (this) {
        PixelCameraState.Photo -> true
        is PixelCameraState.Video -> !recording
        is PixelCameraState.TimeLapse -> !recording
        PixelCameraState.NotRunning,
        PixelCameraState.Unknown,
        PixelCameraState.RecordingUnknownMode,
        -> false
    }

    private fun ExecutionSession.hasUncertainRecordDispatch(): Boolean =
        recordActionAt != null && recordingVerifiedAt == null && stoppedVerifiedAt == null

    private class EngineAbort(
        val result: AutomationRunResult,
    ) : RuntimeException(null, null, false, false)

    private sealed interface TimedCall<out T> {
        data class Completed<T>(
            val value: T,
        ) : TimedCall<T>

        data object TimedOut : TimedCall<Nothing>
    }
}
