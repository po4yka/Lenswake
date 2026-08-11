package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode

internal suspend fun EngineEnvironment.convergeStart(context: RunContext): AutomationRunResult {
    val capture = context.current.capture
    repeat(config.maxConvergenceSteps) {
        val observed = observeStartState(context)
        val completed = convergeStartStep(context, capture, observed)
        if (completed != null) return completed
    }
    fail(
        context,
        failure(
            AutomationFailureCode.AUTOMATION_TIMEOUT,
            "Pixel Camera did not converge within ${config.maxConvergenceSteps} semantic transitions",
        ),
    )
}

private suspend fun EngineEnvironment.observeStartState(context: RunContext): PixelCameraState =
    observeCamera(
        context = context,
        operation = AutomationOperation.INSPECT_CAMERA,
        state = AutomationStateName.INSPECTING_CAMERA_STATE,
        failureCode = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
        failureMessage = "Pixel Camera state could not be inspected",
    ) { it !is PixelCameraState.NotRunning && it !is PixelCameraState.Unknown }

private suspend fun EngineEnvironment.convergeStartStep(
    context: RunContext,
    capture: CaptureConfiguration,
    observed: PixelCameraState,
): AutomationRunResult? = when (observed) {
    PixelCameraState.Photo -> selectInitialMode(context, capture)
    is PixelCameraState.Video -> convergeVideo(context, capture, observed)
    is PixelCameraState.TimeLapse -> convergeTimeLapse(context, capture, observed)
    is PixelCameraState.NightSightTimeLapse -> convergeNightSight(context, capture, observed)
    is PixelCameraState.TimeLapseSpeedPicker -> convergeSpeedPicker(context, capture, observed)
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

private suspend fun EngineEnvironment.selectInitialMode(
    context: RunContext,
    capture: CaptureConfiguration,
): AutomationRunResult? {
    val initialMode = if (capture is CaptureConfiguration.TimeLapse) CaptureMode.VIDEO else capture.mode
    selectCaptureMode(context, initialMode)
    return null
}

private suspend fun EngineEnvironment.convergeVideo(
    context: RunContext,
    capture: CaptureConfiguration,
    observed: PixelCameraState.Video,
): AutomationRunResult? = if (capture is CaptureConfiguration.Video) {
    convergeSimpleCapture(context, capture, observed.recording, observed.lens)
} else {
    refuseModeSwitchWhileRecording(context, observed.recording)
    selectCaptureMode(context, capture.mode)
    null
}

private suspend fun EngineEnvironment.convergeNightSight(
    context: RunContext,
    capture: CaptureConfiguration,
    observed: PixelCameraState.NightSightTimeLapse,
): AutomationRunResult? = if (capture is CaptureConfiguration.NightSightTimeLapse) {
    convergeSimpleCapture(context, capture, observed.recording, observed.lens)
} else {
    refuseModeSwitchWhileRecording(context, observed.recording)
    selectCaptureMode(context, capture.mode)
    null
}

private suspend fun EngineEnvironment.convergeTimeLapse(
    context: RunContext,
    capture: CaptureConfiguration,
    observed: PixelCameraState.TimeLapse,
): AutomationRunResult? {
    if (capture !is CaptureConfiguration.TimeLapse) {
        refuseModeSwitchWhileRecording(context, observed.recording)
        selectCaptureMode(context, capture.mode)
        return null
    }
    return when {
        observed.matchesRecording(capture) && context.current.recordActionAt != null ->
            markRecordingVerified(context)
        observed.matchesRecording(capture) -> fail(
            context,
            failure(
                AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                "Refusing to claim a recording Lenswake did not dispatch",
            ),
        )
        observed.recording -> fail(
            context,
            failure(
                AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                "Pixel Camera is already recording with a different Time Lapse configuration",
            ),
        )
        observed.lens != capture.lens -> {
            dispatchConfiguredLens(context, capture)
            null
        }
        observed.speed != capture.speed -> openSpeedPicker(context)
        else -> startAndVerifyRecording(context, capture)
    }
}

private fun PixelCameraState.TimeLapse.matchesRecording(capture: CaptureConfiguration.TimeLapse): Boolean =
    recording && speed == capture.speed && lens == capture.lens

private suspend fun EngineEnvironment.openSpeedPicker(context: RunContext): AutomationRunResult? {
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
    ) { it is PixelCameraState.TimeLapseSpeedPicker && !it.recording }
    return null
}
