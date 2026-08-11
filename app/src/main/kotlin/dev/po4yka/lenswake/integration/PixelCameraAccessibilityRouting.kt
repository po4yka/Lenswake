package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.TimeLapseSpeed

internal val videoAction = AutomationAction.SELECT_VIDEO
internal val timeLapseAction = AutomationAction.SELECT_TIME_LAPSE
internal val nightSightTimeLapseAction = AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE
internal val openSpeedControlAction = AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL
internal val selectSpeedAction = AutomationAction.SELECT_TIME_LAPSE_SPEED

internal val speedSignals = mapOf(
    PixelCameraStateSignal.TIME_LAPSE_SPEED_AUTO_ACTIVE to TimeLapseSpeed.AUTO,
    PixelCameraStateSignal.TIME_LAPSE_SPEED_X5_ACTIVE to TimeLapseSpeed.X5,
    PixelCameraStateSignal.TIME_LAPSE_SPEED_X10_ACTIVE to TimeLapseSpeed.X10,
    PixelCameraStateSignal.TIME_LAPSE_SPEED_X30_ACTIVE to TimeLapseSpeed.X30,
    PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE to TimeLapseSpeed.X120,
)

internal val lensSignals = mapOf(
    PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE to LensSelection.REAR_MAIN,
    PixelCameraStateSignal.REAR_ULTRAWIDE_LENS_ACTIVE to LensSelection.REAR_ULTRAWIDE,
    PixelCameraStateSignal.REAR_TELEPHOTO_LENS_ACTIVE to LensSelection.REAR_TELEPHOTO,
    PixelCameraStateSignal.FRONT_LENS_ACTIVE to LensSelection.FRONT,
)

internal val lensActions = mapOf(
    LensSelection.REAR_MAIN to AutomationAction.SELECT_REAR_MAIN_LENS,
    LensSelection.REAR_ULTRAWIDE to AutomationAction.SELECT_REAR_ULTRAWIDE_LENS,
    LensSelection.REAR_TELEPHOTO to AutomationAction.SELECT_REAR_TELEPHOTO_LENS,
    LensSelection.FRONT to AutomationAction.SELECT_FRONT_LENS,
)

internal val CaptureMode.startAction: AutomationAction
    get() = when (this) {
        CaptureMode.VIDEO -> AutomationAction.START_VIDEO_RECORDING
        CaptureMode.TIME_LAPSE -> AutomationAction.START_RECORDING
        CaptureMode.NIGHT_SIGHT_TIME_LAPSE ->
            AutomationAction.START_NIGHT_SIGHT_TIME_LAPSE_RECORDING
    }

internal val CaptureMode.stopAction: AutomationAction
    get() = when (this) {
        CaptureMode.VIDEO -> AutomationAction.STOP_VIDEO_RECORDING
        CaptureMode.TIME_LAPSE -> AutomationAction.STOP_RECORDING
        CaptureMode.NIGHT_SIGHT_TIME_LAPSE ->
            AutomationAction.STOP_NIGHT_SIGHT_TIME_LAPSE_RECORDING
    }

internal val stopOptionalSignals = speedSignals.keys + setOf(
    PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
) + lensSignals.keys

internal fun missingActionFailure(action: AutomationAction): AutomationFailure = AutomationFailure(
    code = when (action) {
        AutomationAction.SELECT_VIDEO -> AutomationFailureCode.VIDEO_MODE_NOT_FOUND
        AutomationAction.SELECT_TIME_LAPSE,
        AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE,
        -> AutomationFailureCode.TIME_LAPSE_MODE_NOT_FOUND
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
