package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode

internal suspend fun EngineEnvironment.convergeSpeedPicker(
    context: RunContext,
    capture: CaptureConfiguration,
    picker: PixelCameraState.TimeLapseSpeedPicker,
): AutomationRunResult? {
    val timeLapse = capture as? CaptureConfiguration.TimeLapse
    when {
        timeLapse == null -> closeTimeLapsePickerForModeSwitch(context, picker)
        picker.recording -> refuseModeSwitchWhileRecording(context, true)
        picker.lens != timeLapse.lens && !context.configuredLensObservedBeforeSpeedPicker ->
            closeTimeLapseSpeedControlAndVerify(context, picker.speed)
        picker.speed == timeLapse.speed -> closeTimeLapseSpeedControlAndVerify(context, timeLapse.speed)
        else -> selectTimeLapseSpeed(context, timeLapse)
    }
    return null
}

private suspend fun EngineEnvironment.closeTimeLapsePickerForModeSwitch(
    context: RunContext,
    picker: PixelCameraState.TimeLapseSpeedPicker,
) {
    refuseModeSwitchWhileRecording(context, picker.recording)
    closeTimeLapseSpeedControlAndVerify(context, picker.speed)
}

private suspend fun EngineEnvironment.selectTimeLapseSpeed(
    context: RunContext,
    capture: CaptureConfiguration.TimeLapse,
) {
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
        action = { pixelCamera.selectTimeLapseSpeed(capture.speed, context.profileUse) },
    ) { observed ->
        observed.confirmsSelectedSpeed(capture, context.configuredLensObservedBeforeSpeedPicker)
    }
}

private fun PixelCameraState.confirmsSelectedSpeed(
    capture: CaptureConfiguration.TimeLapse,
    configuredLensAlreadyObserved: Boolean,
): Boolean = when (this) {
    is PixelCameraState.TimeLapse -> !recording && speed == capture.speed
    is PixelCameraState.TimeLapseSpeedPicker ->
        !recording && speed == capture.speed && (lens == capture.lens || configuredLensAlreadyObserved)
    else -> false
}
