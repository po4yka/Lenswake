package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration

/**
 * Convergence for the Auto Night Sight control row of a Night Sight Time Lapse capture. The row
 * only exists inside Time Lapse mode; the engine opens it, selects the ON option, and then treats
 * the capture like any simple capture. Whether Pixel Camera keeps the row open after selection is
 * a build behavior the verification accepts in both shapes: as the dedicated control state or as
 * the confirmed Night Sight Time Lapse mode state.
 */
internal suspend fun EngineEnvironment.convergeNightSightControl(
    context: RunContext,
    capture: CaptureConfiguration,
    control: PixelCameraState.NightSightTimeLapseControl,
): AutomationRunResult? {
    val nightSightCapture = capture as? CaptureConfiguration.NightSightTimeLapse
    when {
        nightSightCapture == null -> {
            // A foreign capture reached the open control row (the camera keeps UI state across
            // sessions). Switch modes through the shared mode chip; the row does not survive it.
            refuseModeSwitchWhileRecording(context, control.recording)
            selectCaptureMode(context, capture.mode)
        }

        control.recording -> {
            refuseModeSwitchWhileRecording(context, true)
        }

        !control.nightSightOn -> {
            selectNightSightOn(context)
        }

        else -> {
            convergeSimpleCapture(context, capture, control.recording, control.lens)
        }
    }
    return null
}

private suspend fun EngineEnvironment.selectNightSightOn(context: RunContext) {
    dispatchAndVerify(
        context = context,
        operation = AutomationOperation.SELECT_NIGHT_SIGHT_TIME_LAPSE,
        actionState = AutomationStateName.SELECTING_NIGHT_SIGHT_TIME_LAPSE,
        verificationState = AutomationStateName.VERIFYING_NIGHT_SIGHT_TIME_LAPSE,
        dispatchFailure =
            failure(
                AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND,
                "Pixel Camera could not select Auto Night Sight in Time Lapse",
            ),
        verificationFailure =
            failure(
                AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_VERIFIED,
                "Pixel Camera did not confirm Auto Night Sight in Time Lapse",
            ),
        action = { pixelCamera.selectNightSightTimeLapse(context.profileUse) },
    ) { observed ->
        when (observed) {
            is PixelCameraState.NightSightTimeLapse -> {
                !observed.recording
            }

            is PixelCameraState.NightSightTimeLapseControl -> {
                observed.nightSightOn && !observed.recording
            }

            else -> {
                false
            }
        }
    }
}

internal suspend fun EngineEnvironment.openNightSightControl(context: RunContext) {
    openNightSightTimeLapseControlAndVerify(
        context = context,
        dispatchFailure =
            failure(
                AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND,
                "Pixel Camera could not open the Night Sight Time Lapse control",
            ),
        verificationFailure =
            failure(
                AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_VERIFIED,
                "Pixel Camera did not expose the Night Sight Time Lapse control row",
            ),
    ) { it is PixelCameraState.NightSightTimeLapseControl && !it.recording }
}
