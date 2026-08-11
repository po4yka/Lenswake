package dev.po4yka.lenswake.core

/**
 * Domain-owned routing contract between a requested capture and a calibrated Pixel Camera profile.
 *
 * Keeping these mappings in core makes profile capability checks and Android dispatch use the same
 * action and signal identities without introducing Android framework types into the domain model.
 */
data class PixelCameraModeContract(
    val selectionAction: AutomationAction,
    val preparationActions: Set<AutomationAction>,
    val requiredSignals: Set<PixelCameraStateSignal>,
    val startAction: AutomationAction,
    val stopAction: AutomationAction,
)

data class PixelCameraLensContract(
    val selectionAction: AutomationAction,
    val activeSignal: PixelCameraStateSignal,
)

data class PixelCameraSpeedContract(
    val activeSignal: PixelCameraStateSignal,
)

val CaptureMode.pixelCameraContract: PixelCameraModeContract
    get() = when (this) {
        CaptureMode.VIDEO -> PixelCameraModeContract(
            selectionAction = AutomationAction.SELECT_VIDEO,
            preparationActions = setOf(AutomationAction.SELECT_VIDEO),
            requiredSignals = setOf(PixelCameraStateSignal.VIDEO_MODE_ACTIVE),
            startAction = AutomationAction.START_VIDEO_RECORDING,
            stopAction = AutomationAction.STOP_VIDEO_RECORDING,
        )
        CaptureMode.TIME_LAPSE -> PixelCameraModeContract(
            selectionAction = AutomationAction.SELECT_TIME_LAPSE,
            preparationActions = setOf(
                AutomationAction.SELECT_VIDEO,
                AutomationAction.SELECT_TIME_LAPSE,
                AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL,
            ),
            requiredSignals = setOf(
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
            ),
            startAction = AutomationAction.START_RECORDING,
            stopAction = AutomationAction.STOP_RECORDING,
        )
        CaptureMode.NIGHT_SIGHT_TIME_LAPSE -> PixelCameraModeContract(
            selectionAction = AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE,
            preparationActions = setOf(AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE),
            requiredSignals = setOf(PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE),
            startAction = AutomationAction.START_NIGHT_SIGHT_TIME_LAPSE_RECORDING,
            stopAction = AutomationAction.STOP_NIGHT_SIGHT_TIME_LAPSE_RECORDING,
        )
    }

val LensSelection.pixelCameraContract: PixelCameraLensContract
    get() = when (this) {
        LensSelection.REAR_MAIN -> PixelCameraLensContract(
            AutomationAction.SELECT_REAR_MAIN_LENS,
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
        )
        LensSelection.REAR_ULTRAWIDE -> PixelCameraLensContract(
            AutomationAction.SELECT_REAR_ULTRAWIDE_LENS,
            PixelCameraStateSignal.REAR_ULTRAWIDE_LENS_ACTIVE,
        )
        LensSelection.REAR_TELEPHOTO -> PixelCameraLensContract(
            AutomationAction.SELECT_REAR_TELEPHOTO_LENS,
            PixelCameraStateSignal.REAR_TELEPHOTO_LENS_ACTIVE,
        )
        LensSelection.FRONT -> PixelCameraLensContract(
            AutomationAction.SELECT_FRONT_LENS,
            PixelCameraStateSignal.FRONT_LENS_ACTIVE,
        )
    }

val TimeLapseSpeed.pixelCameraContract: PixelCameraSpeedContract
    get() = PixelCameraSpeedContract(
        activeSignal = when (this) {
            TimeLapseSpeed.AUTO -> PixelCameraStateSignal.TIME_LAPSE_SPEED_AUTO_ACTIVE
            TimeLapseSpeed.X5 -> PixelCameraStateSignal.TIME_LAPSE_SPEED_X5_ACTIVE
            TimeLapseSpeed.X10 -> PixelCameraStateSignal.TIME_LAPSE_SPEED_X10_ACTIVE
            TimeLapseSpeed.X30 -> PixelCameraStateSignal.TIME_LAPSE_SPEED_X30_ACTIVE
            TimeLapseSpeed.X120 -> PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE
        },
    )

data class PixelCameraCaptureRequirements(
    val actions: Set<AutomationAction>,
    val signals: Set<PixelCameraStateSignal>,
    val speedTarget: TimeLapseSpeed?,
)

object PixelCameraCaptureContract {
    val alwaysRequiredSignals: Set<PixelCameraStateSignal> = setOf(
        PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
        PixelCameraStateSignal.RECORDING_ACTIVE,
        PixelCameraStateSignal.NOT_RECORDING,
    )
}

val CaptureConfiguration.pixelCameraRequirements: PixelCameraCaptureRequirements
    get() {
        val modeContract = mode.pixelCameraContract
        val lensContract = lens.pixelCameraContract
        return PixelCameraCaptureRequirements(
            actions = modeContract.preparationActions +
                modeContract.startAction +
                modeContract.stopAction +
                lensContract.selectionAction,
            signals = PixelCameraCaptureContract.alwaysRequiredSignals +
                modeContract.requiredSignals +
                lensContract.activeSignal +
                listOfNotNull(timeLapseSpeed?.pixelCameraContract?.activeSignal),
            speedTarget = timeLapseSpeed,
        )
    }
