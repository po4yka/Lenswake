package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.supports
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

    /**
     * Record may have reached Pixel Camera, but its recording postcondition is still unverified.
     *
     * Callers must retain delivery and retry START so the engine can reconcile by observation;
     * the write-ahead checkpoint prevents a second Record dispatch.
     */
    data class StartReconciliationRequired(
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
    private val recordingMedia: RecordingMediaPort,
    private val clock: LenswakeClock,
    private val config: AutomationConfig = AutomationConfig.production(),
    private val sleeper: AutomationSleeper = CoroutineAutomationSleeper,
) : AutomationEngine {
    override suspend fun start(sessionId: SessionId): AutomationRunResult = execute(sessionId) { context ->
        if (context.current.cameraOwnershipReleasedAt != null) {
            return@execute AutomationRunResult.AlreadyTerminal(context.current)
        }
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

        context.profileUse = loadProfileUse(context)
        validateSupportedCapture(context)

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
        if (uncertainDispatchAtEntry) {
            var observed = observeCamera(
                context = context,
                operation = AutomationOperation.INSPECT_CAMERA,
                state = AutomationStateName.INSPECTING_CAMERA_STATE,
                failureCode = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                failureMessage = "Pixel Camera state could not be inspected before START reconciliation",
            ) { it !is PixelCameraState.Unknown }
            if (observed is PixelCameraState.NotRunning) {
                observed = launchAndObserveCamera(context)
            }
            reconcileUncertainStart(context, observed)
        } else {
            launchAndObserveCamera(context)
            convergeStart(context)
        }
    }.classifyStartResult()

    override suspend fun stop(sessionId: SessionId): AutomationRunResult = execute(sessionId) { context ->
        val originalStatus = context.current.status
        val originalFailure = context.current.failure
        val uncertainStopAtEntry = context.current.stopActionAt != null
        val retryingOnlyMediaVerification =
            originalStatus == SessionStatus.FAILED &&
                originalFailure?.code == AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED &&
                context.current.stoppedVerifiedAt != null &&
                context.current.recordingVerifiedAt != null
        val preserveFailedOutcome =
            (originalStatus == SessionStatus.FAILED && !retryingOnlyMediaVerification) ||
                context.current.recordingVerifiedAt == null
        val preservedFailure = originalFailure ?: if (preserveFailedOutcome) {
            failure(
                AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                "A dispatched recording was never verified before safe stop recovery",
            )
        } else {
            null
        }

        // The camera can already be safely released while MediaStore is still publishing the
        // video. Resume only the durable save verification after process death or a prior timeout;
        // never reacquire Pixel Camera or dispatch STOP a second time.
        if (context.current.stoppedVerifiedAt != null && context.current.mediaSavedVerifiedAt == null) {
            if (!context.current.mediaVerificationRequired) {
                return@execute finishVerifiedStop(
                    context,
                    preserveFailedOutcome,
                    preservedFailure,
                    completionOperation = AutomationOperation.VERIFY_STOPPED,
                )
            }
            verifySavedRecording(context)
            return@execute finishVerifiedStop(context, preserveFailedOutcome, preservedFailure)
        }
        val hasOwnership = context.current.recordActionAt != null &&
            context.current.cameraOwnershipReleasedAt == null
        val stopOutstanding = context.current.stoppedVerifiedAt == null
        when (context.current.status) {
            SessionStatus.COMPLETED -> return@execute AutomationRunResult.AlreadyTerminal(context.current)
            SessionStatus.CANCELLED -> if (!hasOwnership || !stopOutstanding) {
                return@execute AutomationRunResult.AlreadyTerminal(context.current)
            }
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

        context.profileUse = loadProfileUse(context)

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

        val capture = context.current.capture
        if (
            uncertainStopAtEntry &&
            (beforeStop.isConfirmedRecording(capture) || beforeStop is PixelCameraState.RecordingUnknownMode)
        ) {
            beforeStop = reconcileUncertainStop(context, capture)
        }
        // RecordingUnknownMode means the profile matched its recording control while Pixel Camera
        // hid mode/speed/lens controls. The write-ahead recordActionAt checkpoint is the ownership
        // boundary: STOP is safe, but a missing recordingVerifiedAt keeps the execution failed.
        val ownedRecordingWithHiddenControls = beforeStop is PixelCameraState.RecordingUnknownMode
        if (beforeStop.isConfirmedRecording(capture) || ownedRecordingWithHiddenControls) {
            dispatchRecordingStop(context)
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
        context.transition(
            state = AutomationStateName.VERIFYING_MEDIA_SAVED,
            status = SessionStatus.STOPPING,
            operation = AutomationOperation.VERIFY_STOPPED,
            outcome = AutomationOutcome.SUCCEEDED,
        ) { session, now -> session.copy(stoppedVerifiedAt = session.stoppedVerifiedAt ?: now) }
        if (!context.current.mediaVerificationRequired) {
            return@execute finishVerifiedStop(
                context,
                preserveFailedOutcome,
                preservedFailure,
                completionOperation = AutomationOperation.VERIFY_STOPPED,
            )
        }
        verifySavedRecording(context)
        finishVerifiedStop(context, preserveFailedOutcome, preservedFailure)
    }

    private suspend fun finishVerifiedStop(
        context: RunContext,
        preserveFailedOutcome: Boolean,
        preservedFailure: AutomationFailure?,
        completionOperation: AutomationOperation = AutomationOperation.VERIFY_MEDIA_SAVED,
    ): AutomationRunResult =
        if (preserveFailedOutcome) {
            context.transition(
                state = AutomationStateName.FAILED,
                status = SessionStatus.FAILED,
                operation = completionOperation,
                outcome = AutomationOutcome.SUCCEEDED,
                metadata = mapOf("recovery" to "dispatched_but_unverified_recording"),
            ) { session, _ -> session.copy(failure = preservedFailure) }
            AutomationRunResult.StopVerifiedAfterFailure(context.current, preservedFailure)
        } else {
            context.transition(
                state = AutomationStateName.COMPLETED,
                status = SessionStatus.COMPLETED,
                operation = completionOperation,
                outcome = AutomationOutcome.SUCCEEDED,
            ) { session, _ -> session.copy(failure = null) }
            AutomationRunResult.Succeeded(context.current)
        }

    private suspend fun verifySavedRecording(context: RunContext) {
        val baselineGeneration = context.current.mediaBaselineGeneration ?: fail(
            context,
            failure(
                AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                "Recording output cannot be verified without a pre-Record MediaStore baseline",
            ),
        )
        val mediaStoreVersion = context.current.mediaStoreVersion ?: fail(
            context,
            failure(
                AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                "Recording output cannot be verified without the baseline MediaStore version",
            ),
        )
        val baseline = RecordingMediaBaseline(baselineGeneration, mediaStoreVersion)
        val operation = AutomationOperation.VERIFY_MEDIA_SAVED
        val state = AutomationStateName.VERIFYING_MEDIA_SAVED
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
                when (val invocation = timed(operation) {
                    recordingMedia.findSavedRecording(baseline)
                }) {
                    is TimedCall.Completed -> invocation.value
                    TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                PortResult.Unavailable(
                    operationFailure(
                        AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
                        "Saved recording could not be queried from MediaStore",
                        error,
                    ),
                )
            }
            when (result) {
                is PortResult.Observed -> result.value?.let { evidence ->
                    if (evidence.generationAdded <= baselineGeneration) {
                        lastFailure = failure(
                            AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
                            "MediaStore returned video evidence that does not follow the recording baseline",
                            mapOf(
                                "baselineGeneration" to baselineGeneration.toString(),
                                "candidateGeneration" to evidence.generationAdded.toString(),
                            ),
                        )
                        context.transition(
                            state = state,
                            operation = operation,
                            outcome = AutomationOutcome.FAILED,
                            attempt = attempt,
                            failure = lastFailure,
                        )
                        return@let
                    }
                    context.transition(
                        state = state,
                        operation = operation,
                        outcome = AutomationOutcome.SUCCEEDED,
                        attempt = attempt,
                        metadata = mapOf(
                            "mediaStoreGeneration" to evidence.generationAdded.toString(),
                            "sizeBytes" to evidence.sizeBytes.toString(),
                            "durationMs" to evidence.durationMillis.toString(),
                        ),
                    ) { session, now ->
                        session.copy(
                            mediaSavedVerifiedAt = now,
                            savedMediaGeneration = evidence.generationAdded,
                        )
                    }
                    return
                }

                is PortResult.Unavailable -> lastFailure = result.failure
            }
        }

        fail(
            context,
            lastFailure ?: failure(
                AutomationFailureCode.MEDIA_SAVE_NOT_CONFIRMED,
                "No published Pixel Camera video appeared after the recording baseline",
                mapOf("baselineGeneration" to baselineGeneration.toString()),
            ),
        )
    }

    private suspend fun reconcileUncertainStop(
        context: RunContext,
        capture: CaptureConfiguration,
    ): PixelCameraState {
        val operation = AutomationOperation.VERIFY_STOPPED
        val state = AutomationStateName.VERIFYING_STOPPED
        val policy = config.policyFor(operation)
        var lastObserved: PixelCameraState? = null
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
                metadata = mapOf("reconciliation" to "uncertain_stop"),
            )
            val inspection = try {
                when (val timed = timed(operation) { pixelCamera.inspect(context.profileUse) }) {
                    is TimedCall.Completed -> timed.value
                    TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                PortResult.Unavailable(
                    operationFailure(
                        AutomationFailureCode.STOP_NOT_CONFIRMED,
                        "Pixel Camera state could not be inspected while reconciling an uncertain Stop",
                        error,
                    ),
                )
            }
            when (inspection) {
                is PortResult.Observed -> {
                    lastObserved = inspection.value
                    if (inspection.value.isConfirmedStopped()) {
                        context.transition(
                            state = state,
                            operation = operation,
                            outcome = AutomationOutcome.SUCCEEDED,
                            attempt = attempt,
                            metadata = mapOf("reconciliation" to "uncertain_stop_effect_observed"),
                        )
                        return inspection.value
                    }
                }

                is PortResult.Unavailable -> {
                    lastObserved = null
                    lastFailure = inspection.failure
                }
            }
        }

        val confirmedRecording = lastObserved?.takeIf {
            it.isConfirmedRecording(capture) || it is PixelCameraState.RecordingUnknownMode
        } ?: fail(
            context,
            lastFailure ?: failure(
                AutomationFailureCode.STOP_NOT_CONFIRMED,
                "An uncertain Stop could not be reconciled to a safe camera state",
            ),
        )
        context.transition(
            state = AutomationStateName.RETRYING,
            operation = operation,
            outcome = AutomationOutcome.RETRYING,
            attempt = policy.maxAttempts,
            metadata = mapOf(
                "reconciliation" to "confirmed_recording_before_redispatch",
                "returnState" to AutomationStateName.STOPPING_RECORDING.name,
            ),
        )
        return confirmedRecording
    }

    private suspend fun convergeStart(context: RunContext): AutomationRunResult {
        val capture = context.current.capture
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
                    selectCaptureMode(
                        context,
                        if (capture is CaptureConfiguration.TimeLapse) {
                            CaptureMode.VIDEO
                        } else {
                            capture.mode
                        },
                    )
                }

                is PixelCameraState.Video -> if (capture is CaptureConfiguration.Video) {
                    convergeSimpleCapture(context, capture, state.recording, state.lens)
                        ?.let { return it }
                } else {
                    refuseModeSwitchWhileRecording(context, state.recording)
                    selectCaptureMode(context, capture.mode)
                }

                is PixelCameraState.TimeLapse -> if (capture is CaptureConfiguration.TimeLapse) when {
                    state.recording &&
                        state.speed == capture.speed &&
                        state.lens == capture.lens &&
                        context.current.recordActionAt != null -> {
                        return markRecordingVerified(context)
                    }

                    state.recording &&
                        state.speed == capture.speed &&
                        state.lens == capture.lens -> fail(
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

                    state.lens != capture.lens -> dispatchConfiguredLens(context, capture)

                    state.speed != capture.speed -> {
                        context.configuredLensObservedBeforeSpeedPicker = true
                        openTimeLapseSpeedControlAndVerify(
                            context = context,
                            dispatchFailure = failure(
                                AutomationFailureCode.TIME_LAPSE_SPEED_NOT_FOUND,
                                "Pixel Camera could not open the Time Lapse speed control",
                            ),
                            verificationFailure = failure(
                                AutomationFailureCode.TIME_LAPSE_SPEED_NOT_VERIFIED,
                                "Pixel Camera did not expose the Time Lapse speed picker",
                            ),
                        ) {
                            it is PixelCameraState.TimeLapseSpeedPicker && !it.recording
                        }
                    }

                    else -> return startAndVerifyRecording(context, capture)
                } else {
                    refuseModeSwitchWhileRecording(context, state.recording)
                    selectCaptureMode(context, capture.mode)
                }

                is PixelCameraState.NightSightTimeLapse ->
                    if (capture is CaptureConfiguration.NightSightTimeLapse) {
                        convergeSimpleCapture(context, capture, state.recording, state.lens)
                            ?.let { return it }
                    } else {
                        refuseModeSwitchWhileRecording(context, state.recording)
                        selectCaptureMode(context, capture.mode)
                    }

                is PixelCameraState.TimeLapseSpeedPicker -> {
                    val timeLapse = capture as? CaptureConfiguration.TimeLapse
                    if (timeLapse == null) {
                        if (state.recording) refuseModeSwitchWhileRecording(context, true)
                        closeTimeLapseSpeedControlAndVerify(context, state.speed)
                        return@repeat
                    }
                    if (state.recording) {
                        fail(
                            context,
                            failure(
                                AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                                "Pixel Camera exposed the Time Lapse speed picker while recording",
                            ),
                        )
                    }
                    if (
                        state.lens != timeLapse.lens &&
                        !context.configuredLensObservedBeforeSpeedPicker
                    ) {
                        closeTimeLapseSpeedControlAndVerify(context, state.speed)
                        return@repeat
                    }
                    if (state.speed == timeLapse.speed) {
                        closeTimeLapseSpeedControlAndVerify(context, timeLapse.speed)
                        return@repeat
                    }
                    dispatchAndVerify(
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
                        action = { pixelCamera.selectTimeLapseSpeed(timeLapse.speed, context.profileUse) },
                    ) {
                        when (it) {
                            is PixelCameraState.TimeLapse -> !it.recording && it.speed == timeLapse.speed
                            is PixelCameraState.TimeLapseSpeedPicker ->
                                !it.recording &&
                                    it.speed == timeLapse.speed &&
                                    (it.lens == timeLapse.lens ||
                                        context.configuredLensObservedBeforeSpeedPicker)
                            else -> false
                        }
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

    private suspend fun selectCaptureMode(
        context: RunContext,
        mode: CaptureMode,
    ) {
        when (mode) {
            CaptureMode.VIDEO -> dispatchAndVerify(
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
                action = { pixelCamera.selectVideo(context.profileUse) },
            ) { it is PixelCameraState.Video && !it.recording }

            CaptureMode.TIME_LAPSE -> dispatchAndVerify(
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
                action = { pixelCamera.selectTimeLapse(context.profileUse) },
            ) { it is PixelCameraState.TimeLapse && !it.recording }

            CaptureMode.NIGHT_SIGHT_TIME_LAPSE -> dispatchAndVerify(
                context = context,
                operation = AutomationOperation.SELECT_NIGHT_SIGHT_TIME_LAPSE,
                actionState = AutomationStateName.SELECTING_NIGHT_SIGHT_TIME_LAPSE,
                verificationState = AutomationStateName.VERIFYING_NIGHT_SIGHT_TIME_LAPSE,
                dispatchFailure = failure(
                    AutomationFailureCode.TIME_LAPSE_MODE_NOT_FOUND,
                    "Pixel Camera could not select Night Sight Time Lapse mode",
                ),
                verificationFailure = failure(
                    AutomationFailureCode.TIME_LAPSE_MODE_NOT_VERIFIED,
                    "Pixel Camera did not confirm Night Sight Time Lapse mode",
                ),
                action = { pixelCamera.selectNightSightTimeLapse(context.profileUse) },
            ) { it is PixelCameraState.NightSightTimeLapse && !it.recording }
        }
    }

    private suspend fun convergeSimpleCapture(
        context: RunContext,
        capture: CaptureConfiguration,
        recording: Boolean,
        observedLens: LensSelection?,
    ): AutomationRunResult? = when {
        recording && observedLens == capture.lens && context.current.recordActionAt != null ->
            markRecordingVerified(context)
        recording && observedLens == capture.lens -> fail(
            context,
            failure(
                AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                "Refusing to claim a recording Lenswake did not dispatch",
            ),
        )
        recording -> fail(
            context,
            failure(
                AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                "Pixel Camera is already recording with a different capture configuration",
            ),
        )
        observedLens != capture.lens -> {
            dispatchConfiguredLens(context, capture)
            null
        }
        else -> startAndVerifyRecording(context, capture)
    }

    private suspend fun dispatchConfiguredLens(context: RunContext, capture: CaptureConfiguration) {
        dispatchAndVerify(
            context = context,
            operation = AutomationOperation.SELECT_LENS,
            actionState = AutomationStateName.SELECTING_LENS,
            verificationState = AutomationStateName.VERIFYING_LENS,
            dispatchFailure = failure(
                AutomationFailureCode.LENS_NOT_FOUND,
                "Pixel Camera could not select ${capture.lens}",
            ),
            verificationFailure = failure(
                AutomationFailureCode.LENS_NOT_VERIFIED,
                "Pixel Camera did not confirm ${capture.lens}",
            ),
            action = { pixelCamera.selectLens(capture.lens, context.profileUse) },
        ) {
            !it.isRecording() &&
                it.observedLens() == capture.lens &&
                it.observedMode() == capture.mode
        }
    }

    private suspend fun startAndVerifyRecording(
        context: RunContext,
        capture: CaptureConfiguration,
    ): AutomationRunResult.Succeeded {
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
            failureMessage = "Pixel Camera did not confirm ${capture.mode} recording",
        ) { it.isConfirmedRecording(capture) }
        return markRecordingVerified(context)
    }

    private suspend fun refuseModeSwitchWhileRecording(context: RunContext, recording: Boolean) {
        if (recording) {
            fail(
                context,
                failure(
                    AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                    "Pixel Camera is recording in a mode that Lenswake must not alter",
                ),
            )
        }
    }

    private suspend fun closeTimeLapseSpeedControlAndVerify(
        context: RunContext,
        expectedSpeed: TimeLapseSpeed?,
    ) {
        dispatchAndVerify(
            context = context,
            operation = AutomationOperation.CLOSE_TIME_LAPSE_SPEED_CONTROL,
            actionState = AutomationStateName.CLOSING_TIME_LAPSE_SPEED_CONTROL,
            verificationState = AutomationStateName.VERIFYING_TIME_LAPSE_SPEED_CLOSED,
            dispatchFailure = failure(
                AutomationFailureCode.TIME_LAPSE_SPEED_CONTROL_CLOSE_FAILED,
                "Pixel Camera could not close the confirmed Time Lapse speed picker",
            ),
            verificationFailure = failure(
                AutomationFailureCode.TIME_LAPSE_SPEED_NOT_VERIFIED,
                "Pixel Camera did not confirm the closed Time Lapse speed picker",
            ),
            action = {
                pixelCamera.closeTimeLapseSpeedControl(expectedSpeed, context.profileUse)
            },
        ) { observed ->
            observed is PixelCameraState.TimeLapse &&
                !observed.recording &&
                (expectedSpeed == null || observed.speed == expectedSpeed)
        }
    }

    private suspend fun reconcileUncertainStart(
        context: RunContext,
        observed: PixelCameraState,
    ): AutomationRunResult {
        val capture = context.current.capture
        return if (observed.isConfirmedRecording(capture)) {
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

    private fun AutomationRunResult.classifyStartResult(): AutomationRunResult =
        if (this is AutomationRunResult.Failed && session.hasUncertainRecordDispatch()) {
            AutomationRunResult.StartReconciliationRequired(session, failure)
        } else {
            this
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
            action = { pixelCamera.launchSecureCamera(context.profileUse) },
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

    /**
     * The speed-picker opener is idempotent: a dispatched gesture may be ignored while Pixel
     * Camera finishes a cold launch, so convergence retries the opener rather than only polling.
     * This is deliberately not shared with Record or Stop, whose dispatched actions are unsafe to
     * repeat until their external side effects have been reconciled.
     */
    private suspend fun openTimeLapseSpeedControlAndVerify(
        context: RunContext,
        dispatchFailure: AutomationFailure,
        verificationFailure: AutomationFailure,
        predicate: (PixelCameraState) -> Boolean,
    ) {
        val operation = AutomationOperation.OPEN_TIME_LAPSE_SPEED_CONTROL
        val actionState = AutomationStateName.OPENING_TIME_LAPSE_SPEED_CONTROL
        val verificationState = AutomationStateName.VERIFYING_TIME_LAPSE_SPEED_CONTROL
        val policy = config.policyFor(operation)
        var lastFailure: AutomationFailure? = null

        suspend fun pickerIsOpen(attempt: Int): Boolean {
            context.transition(
                state = verificationState,
                operation = operation,
                outcome = AutomationOutcome.STARTED,
                attempt = attempt,
            )
            val inspection = try {
                when (val timed = timed(operation) { pixelCamera.inspect(context.profileUse) }) {
                    is TimedCall.Completed -> timed.value
                    TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                PortResult.Unavailable(
                    operationFailure(verificationFailure.code, verificationFailure.message, error),
                )
            }
            return when (inspection) {
                is PortResult.Observed -> predicate(inspection.value).also { open ->
                    if (open) {
                        context.transition(
                            state = verificationState,
                            operation = operation,
                            outcome = AutomationOutcome.SUCCEEDED,
                            attempt = attempt,
                        )
                    }
                }

                is PortResult.Unavailable -> {
                    lastFailure = inspection.failure
                    false
                }
            }
        }

        for (attempt in 1..policy.maxAttempts) {
            if (attempt > 1) {
                retryTransition(context, operation, attempt, actionState)
                sleeper.sleep(policy.delayBeforeAttempt(attempt))
            }

            if (pickerIsOpen(attempt)) return

            context.transition(
                state = actionState,
                operation = operation,
                outcome = AutomationOutcome.STARTED,
                attempt = attempt,
            )
            val dispatch = try {
                when (val timed = timed(operation) {
                    pixelCamera.openTimeLapseSpeedControl(context.profileUse)
                }) {
                    is TimedCall.Completed -> timed.value
                    TimedCall.TimedOut -> ActionDispatch.Rejected(timeoutFailure(operation))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ActionDispatch.Rejected(
                    operationFailure(dispatchFailure.code, dispatchFailure.message, error),
                )
            }
            when (dispatch) {
                is ActionDispatch.Dispatched -> context.transition(
                    state = actionState,
                    operation = operation,
                    outcome = AutomationOutcome.DISPATCHED,
                    method = dispatch.method,
                    attempt = attempt,
                )

                is ActionDispatch.Rejected -> {
                    lastFailure = dispatch.failure
                    continue
                }
            }

            if (pickerIsOpen(attempt)) return
        }

        fail(context, lastFailure ?: verificationFailure)
    }

    private suspend fun dispatchRecordingStart(context: RunContext) {
        captureMediaBaseline(context)
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
                timed(operation) { pixelCamera.startRecording(context.current.capture.mode, context.profileUse) }
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

    private suspend fun captureMediaBaseline(context: RunContext) {
        if (context.current.mediaBaselineGeneration != null) return
        val operation = AutomationOperation.CAPTURE_MEDIA_BASELINE
        val state = AutomationStateName.CAPTURING_MEDIA_BASELINE
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
                when (val invocation = timed(operation, recordingMedia::captureBaseline)) {
                    is TimedCall.Completed -> invocation.value
                    TimedCall.TimedOut -> PortResult.Unavailable(timeoutFailure(operation))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                PortResult.Unavailable(
                    operationFailure(
                        AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                        "MediaStore baseline could not be captured",
                        error,
                    ),
                )
            }
            when (result) {
                is PortResult.Observed -> {
                    context.transition(
                        state = state,
                        operation = operation,
                        outcome = AutomationOutcome.SUCCEEDED,
                        attempt = attempt,
                        metadata = mapOf(
                            "mediaStoreGeneration" to result.value.generation.toString(),
                            "mediaStoreVersion" to result.value.version,
                        ),
                    ) { session, _ ->
                        session.copy(
                            mediaBaselineGeneration = result.value.generation,
                            mediaStoreVersion = result.value.version,
                        )
                    }
                    return
                }

                is PortResult.Unavailable -> lastFailure = result.failure
            }
        }

        fail(
            context,
            lastFailure ?: failure(
                AutomationFailureCode.MEDIA_BASELINE_UNAVAILABLE,
                "MediaStore baseline could not be captured",
            ),
        )
    }

    private suspend fun dispatchRecordingStop(context: RunContext) {
        val operation = AutomationOperation.STOP_RECORDING
        val state = AutomationStateName.STOPPING_RECORDING
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
            ) { session, now -> session.copy(stopActionAt = session.stopActionAt ?: now) }

            val invocation = try {
                timed(operation) { pixelCamera.stopRecording(context.current.capture.mode, context.profileUse) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(
                    context,
                    operationFailure(
                        AutomationFailureCode.STOP_ACTION_FAILED,
                        "Stop dispatch threw after a possible external side effect",
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
                        ) { session, _ -> session.copy(stopActionAt = null) }
                    }
                }
            }
        }

        fail(
            context,
            lastRejection ?: failure(
                AutomationFailureCode.STOP_ACTION_FAILED,
                "Pixel Camera definitively rejected the Stop action",
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
                when (val timed = timed(operation) { pixelCamera.inspect(context.profileUse) }) {
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
        lateinit var profileUse: ProfileUse
        var configuredLensObservedBeforeSpeedPicker: Boolean = false

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

    private suspend fun loadProfileUse(context: RunContext): ProfileUse {
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
        return ProfileUse(
            profile = profile,
            kind = when (context.current.kind) {
                SessionKind.SCHEDULED -> ProfileUse.Kind.UNATTENDED
                SessionKind.REHEARSAL -> ProfileUse.Kind.REHEARSAL
            },
        )
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
        val capture = context.current.capture
        if (!context.profileUse.profile.supports(capture)) {
            fail(
                context,
                failure(
                    AutomationFailureCode.UNSUPPORTED_CAPTURE_CONFIGURATION,
                    "The Pixel Camera profile has no verified selectors for the capture configuration",
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
        state == AutomationStateName.VERIFYING_MEDIA_SAVED &&
            operation == AutomationOperation.VERIFY_MEDIA_SAVED &&
            outcome == AutomationOutcome.SUCCEEDED -> "automation.media.save_verified"
        state == AutomationStateName.COMPLETED && operation == AutomationOperation.VERIFY_STOPPED ->
            "automation.record.stop_verified_media_unavailable_legacy"
        state == AutomationStateName.COMPLETED -> "automation.record.stop_and_save_verified"
        state == AutomationStateName.FAILED &&
            operation == AutomationOperation.VERIFY_MEDIA_SAVED &&
            outcome == AutomationOutcome.SUCCEEDED -> "automation.record.stop_and_save_verified_after_failure"
        state == AutomationStateName.FAILED &&
            operation == AutomationOperation.VERIFY_STOPPED &&
            outcome == AutomationOutcome.SUCCEEDED -> "automation.record.stop_verified_after_failure"
        state == AutomationStateName.FAILED -> "automation.failed"
        outcome == AutomationOutcome.DISPATCHED && operation != null ->
            "automation.${operation.name.lowercase()}.dispatched"
        else -> "automation.state.${state.name.lowercase()}"
    }

    private fun PixelCameraState.isConfirmedRecording(capture: CaptureConfiguration): Boolean = when (capture) {
        is CaptureConfiguration.Video ->
            this is PixelCameraState.Video && recording && lens == capture.lens
        is CaptureConfiguration.TimeLapse ->
            this is PixelCameraState.TimeLapse &&
                recording &&
                speed == capture.speed &&
                lens == capture.lens
        is CaptureConfiguration.NightSightTimeLapse ->
            this is PixelCameraState.NightSightTimeLapse && recording && lens == capture.lens
    }

    private fun PixelCameraState.isConfirmedStopped(): Boolean = when (this) {
        PixelCameraState.Photo -> true
        is PixelCameraState.Video -> !recording
        is PixelCameraState.TimeLapse -> !recording
        is PixelCameraState.TimeLapseSpeedPicker -> !recording
        is PixelCameraState.NightSightTimeLapse -> !recording
        PixelCameraState.NotRunning,
        PixelCameraState.Unknown,
        PixelCameraState.RecordingUnknownMode,
        -> false
    }

    private fun PixelCameraState.isRecording(): Boolean = when (this) {
        is PixelCameraState.Video -> recording
        is PixelCameraState.TimeLapse -> recording
        is PixelCameraState.TimeLapseSpeedPicker -> recording
        is PixelCameraState.NightSightTimeLapse -> recording
        PixelCameraState.RecordingUnknownMode -> true
        PixelCameraState.Photo,
        PixelCameraState.NotRunning,
        PixelCameraState.Unknown,
        -> false
    }

    private fun PixelCameraState.observedLens(): LensSelection? = when (this) {
        is PixelCameraState.Video -> lens
        is PixelCameraState.TimeLapse -> lens
        is PixelCameraState.TimeLapseSpeedPicker -> lens
        is PixelCameraState.NightSightTimeLapse -> lens
        else -> null
    }

    private fun PixelCameraState.observedMode(): CaptureMode? = when (this) {
        is PixelCameraState.Video -> CaptureMode.VIDEO
        is PixelCameraState.TimeLapse,
        is PixelCameraState.TimeLapseSpeedPicker,
        -> CaptureMode.TIME_LAPSE
        is PixelCameraState.NightSightTimeLapse -> CaptureMode.NIGHT_SIGHT_TIME_LAPSE
        else -> null
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
