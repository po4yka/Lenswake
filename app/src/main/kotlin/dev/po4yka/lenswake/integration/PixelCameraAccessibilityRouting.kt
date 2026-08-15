package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.pixelCameraContract

internal val openSpeedControlAction = AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL
internal val selectSpeedAction = AutomationAction.SELECT_TIME_LAPSE_SPEED

internal val speedSignals = TimeLapseSpeed.entries.associateBy {
    it.pixelCameraContract.activeSignal
}

internal val lensSignals = LensSelection.entries.associateBy {
    it.pixelCameraContract.activeSignal
}

internal val lensActions = LensSelection.entries.associateWith {
    it.pixelCameraContract.selectionAction
}

internal val CaptureMode.startAction: AutomationAction
    get() = pixelCameraContract.startAction

internal val CaptureMode.stopAction: AutomationAction
    get() = pixelCameraContract.stopAction

internal val CaptureMode.selectionAction: AutomationAction
    get() = pixelCameraContract.selectionAction

internal val stopOptionalSignals = speedSignals.keys + setOf(
    PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
) + lensSignals.keys

internal fun missingActionFailure(action: AutomationAction): AutomationFailure = AutomationFailure(
    code = when (action) {
        AutomationAction.SELECT_VIDEO -> AutomationFailureCode.VIDEO_MODE_NOT_FOUND
        AutomationAction.SELECT_VIDEO_RESOLUTION_4K,
        AutomationAction.SELECT_VIDEO_FRAME_RATE_60,
        -> AutomationFailureCode.VIDEO_MODE_NOT_FOUND
        AutomationAction.SELECT_TIME_LAPSE -> AutomationFailureCode.TIME_LAPSE_MODE_NOT_FOUND
        AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE ->
            AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND
        AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL,
        AutomationAction.SELECT_TIME_LAPSE_SPEED,
        -> AutomationFailureCode.TIME_LAPSE_SPEED_NOT_FOUND
        AutomationAction.SELECT_REAR_MAIN_LENS,
        AutomationAction.SELECT_REAR_ULTRAWIDE_LENS,
        AutomationAction.SELECT_REAR_TELEPHOTO_LENS,
        AutomationAction.SELECT_FRONT_LENS,
        -> AutomationFailureCode.LENS_NOT_FOUND
        AutomationAction.START_RECORDING,
        AutomationAction.START_VIDEO_RECORDING,
        AutomationAction.START_NIGHT_SIGHT_TIME_LAPSE_RECORDING,
        -> AutomationFailureCode.RECORD_CONTROL_NOT_FOUND
        AutomationAction.STOP_RECORDING,
        AutomationAction.STOP_VIDEO_RECORDING,
        AutomationAction.STOP_NIGHT_SIGHT_TIME_LAPSE_RECORDING,
        -> AutomationFailureCode.STOP_CONTROL_NOT_FOUND
    },
    message = "No safe Pixel Camera target was available for $action",
)

internal fun accessibilityFailure(
    code: AutomationFailureCode,
    message: String,
): AutomationFailure = AutomationFailure(code = code, message = message)
