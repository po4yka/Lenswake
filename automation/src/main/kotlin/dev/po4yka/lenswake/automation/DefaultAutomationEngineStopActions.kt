package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.SessionStatus

internal suspend fun EngineEnvironment.prepareStop(context: RunContext) {
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
}

internal suspend fun EngineEnvironment.locateRecording(context: RunContext): PixelCameraState {
    val observed = observeCamera(
        context = context,
        operation = AutomationOperation.INSPECT_CAMERA,
        state = AutomationStateName.INSPECTING_RECORDING_STATE,
        failureCode = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
        failureMessage = "Pixel Camera state could not be inspected before stop",
    ) { it !is PixelCameraState.Unknown }
    return if (observed is PixelCameraState.NotRunning) {
        launchAndObserveCamera(context, AutomationStateName.LOCATING_PIXEL_CAMERA)
    } else {
        observed
    }
}

internal fun PixelCameraState.isOwnedOrUnknownRecording(capture: CaptureConfiguration): Boolean =
    isConfirmedRecording(capture) || this is PixelCameraState.RecordingUnknownMode

internal suspend fun EngineEnvironment.dispatchStopIfRequired(
    context: RunContext,
    beforeStop: PixelCameraState,
    capture: CaptureConfiguration,
) {
    when {
        beforeStop.isOwnedOrUnknownRecording(capture) -> dispatchRecordingStop(context)
        !beforeStop.isConfirmedStopped() -> fail(
            context,
            failure(
                AutomationFailureCode.STOP_NOT_CONFIRMED,
                "The active recording could not be identified safely",
            ),
        )
    }
}

internal suspend fun EngineEnvironment.verifyStopped(context: RunContext) {
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
}

internal suspend fun EngineEnvironment.inspectDuringStopReconciliation(
    context: RunContext,
    operation: AutomationOperation,
): PortResult<PixelCameraState> = inspectCameraHandlingDialog(
    context = context,
    operation = operation,
    failureCode = AutomationFailureCode.STOP_NOT_CONFIRMED,
    failureMessage = "Pixel Camera state inspection failed during uncertain Stop reconciliation",
)

internal suspend fun markUncertainStopReconciled(
    context: RunContext,
    operation: AutomationOperation,
    attempt: Int,
) {
    context.transition(
        state = AutomationStateName.VERIFYING_STOPPED,
        operation = operation,
        outcome = AutomationOutcome.SUCCEEDED,
        attempt = attempt,
        metadata = mapOf("reconciliation" to "uncertain_stop_effect_observed"),
    )
}

internal suspend fun markStopReadyForRedispatch(
    context: RunContext,
    operation: AutomationOperation,
    attempt: Int,
) {
    context.transition(
        state = AutomationStateName.RETRYING,
        operation = operation,
        outcome = AutomationOutcome.RETRYING,
        attempt = attempt,
        metadata = mapOf(
            "reconciliation" to "confirmed_recording_before_redispatch",
            "returnState" to AutomationStateName.STOPPING_RECORDING.name,
        ),
    )
}
