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

internal suspend fun EngineEnvironment.start(sessionId: SessionId): AutomationRunResult =
    execute(sessionId) { context ->
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
            SessionStatus.STOPPING -> return@execute rejectedState(
                context.current,
                "Cannot start a session while it is stopping",
            )
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

internal suspend fun EngineEnvironment.selectCaptureMode(
        context: RunContext,
        mode: CaptureMode,
    ) {
        val failureCodes = mode.selectionFailureCodes
        when (mode) {
            CaptureMode.VIDEO -> dispatchAndVerify(
                context = context,
                operation = AutomationOperation.SELECT_VIDEO,
                actionState = AutomationStateName.SELECTING_VIDEO,
                verificationState = AutomationStateName.VERIFYING_VIDEO,
                dispatchFailure = failure(
                    failureCodes.dispatch,
                    "Pixel Camera could not select Video mode",
                ),
                verificationFailure = failure(
                    failureCodes.verification,
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
                    failureCodes.dispatch,
                    "Pixel Camera could not select Time Lapse mode",
                ),
                verificationFailure = failure(
                    failureCodes.verification,
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
                    failureCodes.dispatch,
                    "Pixel Camera could not select Night Sight Time Lapse mode",
                ),
                verificationFailure = failure(
                    failureCodes.verification,
                    "Pixel Camera did not confirm Night Sight Time Lapse mode",
                ),
                action = { pixelCamera.selectNightSightTimeLapse(context.profileUse) },
            ) { it is PixelCameraState.NightSightTimeLapse && !it.recording }
        }
    }

internal suspend fun EngineEnvironment.convergeSimpleCapture(
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

internal suspend fun EngineEnvironment.dispatchConfiguredLens(
    context: RunContext,
    capture: CaptureConfiguration,
) {
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

internal suspend fun EngineEnvironment.startAndVerifyRecording(
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

internal suspend fun EngineEnvironment.refuseModeSwitchWhileRecording(
    context: RunContext,
    recording: Boolean,
) {
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

internal suspend fun EngineEnvironment.closeTimeLapseSpeedControlAndVerify(
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

internal suspend fun EngineEnvironment.reconcileUncertainStart(
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

internal fun AutomationRunResult.classifyStartResult(): AutomationRunResult =
        if (this is AutomationRunResult.Failed && session.hasUncertainRecordDispatch()) {
            AutomationRunResult.StartReconciliationRequired(session, failure)
        } else {
            this
        }

internal suspend fun EngineEnvironment.markRecordingVerified(
    context: RunContext,
): AutomationRunResult.Succeeded {
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
