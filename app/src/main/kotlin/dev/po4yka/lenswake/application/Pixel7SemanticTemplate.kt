package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfileSource
import dev.po4yka.lenswake.core.SupportTier
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import dev.po4yka.lenswake.platform.PIXEL_CAMERA_PACKAGE
import dev.po4yka.lenswake.platform.SUPPORTED_PIXEL_CAMERA_IDENTITY

/** Independent live Pixel 7 semantic definition; beta provenance never admits that runtime. */
internal object Pixel7SemanticTemplate {
    private val selectors = Pixel7SelectorFactory()
    private val speedMultipliers = mapOf(
        TimeLapseSpeed.X5 to "5",
        TimeLapseSpeed.X10 to "10",
        TimeLapseSpeed.X30 to "30",
        TimeLapseSpeed.X120 to "120",
    )

    val profile: PixelCameraProfile = PixelCameraProfile(
        id = ProfileId("pixel-7-beta-cp41-260717-006-physical-template-v5"),
        environment = environment(),
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        supportTier = SupportTier.EXPERIMENTAL,
        source = ProfileSource.PHYSICAL_TEMPLATE,
        selectorTemplate = PixelCameraTemplateKind.SEMANTIC_STANDARD.reference,
        targets = actionTargets(),
        speedTargets = speedTargets(),
        stateSignals = modeAndSpeedStateSignals() + lensAndRecordingStateSignals(),
        compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
        verifiedAt = null,
    )

    private fun environment() = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 7",
        deviceCodename = "panther",
        androidSdk = 37,
        androidBuildFingerprint =
            "google/panther_beta/panther:DEV/CP41.260717.006/15938186:user/release-keys",
        cameraPackage = PIXEL_CAMERA_PACKAGE,
        cameraVersionCode = SUPPORTED_PIXEL_CAMERA_IDENTITY.versionCode,
        cameraSigningCertificateSha256 = SUPPORTED_PIXEL_CAMERA_IDENTITY.signingCertificate.hex,
        localeTag = "en-US",
        displayWidthPx = 1_080,
        displayHeightPx = 2_400,
        densityDpi = 420,
    )

    private fun actionTargets(): Map<AutomationAction, UiSelectorSet> = mapOf(
        AutomationAction.SELECT_VIDEO to selectors.action(resourceId = "video_supermode", minimumScore = 110),
        AutomationAction.SELECT_VIDEO_RESOLUTION_4K to selectors.action(
            contentDescription = "4K Ultra HD",
            minimumScore = 70,
        ),
        AutomationAction.SELECT_VIDEO_FRAME_RATE_60 to selectors.action(
            contentDescription = "60 FPS",
            minimumScore = 70,
        ),
        AutomationAction.SELECT_TIME_LAPSE to UiSelectorSet(
            selectors = listOf(
                selectors.node(
                    resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                    contentDescription = "Switch to Time Lapse Mode",
                    text = "Time Lapse",
                    requiresClickable = false,
                ),
                selectors.node(
                    resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                    contentDescription = "Time Lapse",
                    text = "Time Lapse",
                    requiresClickable = false,
                ),
            ),
            minimumScore = 190,
        ),
        AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL to selectors.action(
            contentDescription = "Time Lapse control",
            minimumScore = 60,
            requiresClickable = false,
        ),
        AutomationAction.SELECT_REAR_MAIN_LENS to UiSelectorSet(
            selectors = listOf(
                selectors.node(resourceId = "zoom_toggle_1", text = "1", requiresClickable = false),
                selectors.node(
                    resourceId = "$PIXEL_CAMERA_PACKAGE:id/camera_switch_button",
                    contentDescription = "Switch to back camera",
                ),
            ),
            minimumScore = 130,
        ),
        AutomationAction.SELECT_REAR_ULTRAWIDE_LENS to selectors.action(
            resourceId = "zoom_toggle_.7",
            text = ".7",
            minimumScore = 130,
            requiresClickable = false,
        ),
        AutomationAction.SELECT_FRONT_LENS to selectors.action(
            resourceId = "$PIXEL_CAMERA_PACKAGE:id/camera_switch_button",
            contentDescription = "Switch to front camera",
            minimumScore = 170,
        ),
        AutomationAction.START_RECORDING to shutterAction("Start time lapse"),
        AutomationAction.STOP_RECORDING to shutterAction("Stop time lapse"),
        AutomationAction.START_VIDEO_RECORDING to shutterAction("Start video"),
        AutomationAction.STOP_VIDEO_RECORDING to shutterAction("Stop video"),
    )

    private fun speedTargets(): Map<TimeLapseSpeed, UiSelectorSet> = mapOf(
        TimeLapseSpeed.AUTO to selectors.action(
            contentDescription = "Time Lapse auto speed",
            text = "Auto",
            minimumScore = 100,
        ),
        TimeLapseSpeed.X5 to speedAction(TimeLapseSpeed.X5),
        TimeLapseSpeed.X10 to speedAction(TimeLapseSpeed.X10),
        TimeLapseSpeed.X30 to speedAction(TimeLapseSpeed.X30),
        TimeLapseSpeed.X120 to speedAction(TimeLapseSpeed.X120),
    )

    private fun modeAndSpeedStateSignals(): Map<PixelCameraStateSignal, UiSelectorSet> = mapOf(
        PixelCameraStateSignal.PHOTO_MODE_ACTIVE to modeState("Photo"),
        PixelCameraStateSignal.VIDEO_MODE_ACTIVE to modeState("Video"),
        PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE to selectors.state(
            contentDescription = "4K Ultra HD",
            expectedSelected = true,
            minimumScore = 75,
        ),
        PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE to selectors.state(
            contentDescription = "60 FPS",
            expectedSelected = true,
            minimumScore = 75,
        ),
        PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE to modeState("Time Lapse"),
        PixelCameraStateSignal.TIME_LAPSE_SPEED_AUTO_ACTIVE to speedState(
            "Time Lapse auto speed",
            "Auto",
        ),
        PixelCameraStateSignal.TIME_LAPSE_SPEED_X5_ACTIVE to speedState(
            "Time Lapse 5 times speed",
            "5×",
        ),
        PixelCameraStateSignal.TIME_LAPSE_SPEED_X10_ACTIVE to speedState(
            "Time Lapse 10 times speed",
            "10×",
        ),
        PixelCameraStateSignal.TIME_LAPSE_SPEED_X30_ACTIVE to speedState(
            "Time Lapse 30 times speed",
            "30×",
        ),
        PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE to speedState(
            "Time Lapse 120 times speed",
            "120×",
        ),
        PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN to speedPickerState(),
    )

    private fun lensAndRecordingStateSignals(): Map<PixelCameraStateSignal, UiSelectorSet> = mapOf(
        PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE to selectors.state(
            resourceId = "zoom_toggle_1×",
            text = "1×",
            minimumScore = 130,
        ),
        PixelCameraStateSignal.REAR_ULTRAWIDE_LENS_ACTIVE to selectors.state(
            resourceId = "zoom_toggle_.7×",
            text = ".7×",
            minimumScore = 130,
        ),
        PixelCameraStateSignal.FRONT_LENS_ACTIVE to selectors.state(
            resourceId = "$PIXEL_CAMERA_PACKAGE:id/camera_switch_button",
            contentDescription = "Switch to back camera",
            minimumScore = 160,
        ),
        PixelCameraStateSignal.RECORDING_ACTIVE to shutterStates("Stop video", "Stop time lapse"),
        PixelCameraStateSignal.NOT_RECORDING to shutterStates(
            "Take photo",
            "Start video",
            "Start time lapse",
        ),
    )

    private fun speedPickerState() = UiSelectorSet(
        selectors = TimeLapseSpeed.entries.map { speed ->
            val description = speedMultipliers[speed]?.let { "Time Lapse $it times speed" }
                ?: "Time Lapse auto speed"
            selectors.node(contentDescription = description, requiresClickable = false)
        },
        minimumScore = 60,
    )

    private fun shutterStates(vararg descriptions: String) = UiSelectorSet(
        selectors = descriptions.map { description ->
            selectors.node(
                resourceId = "$PIXEL_CAMERA_PACKAGE:id/shutter_button",
                contentDescription = description,
                requiresClickable = false,
            )
        },
        minimumScore = 160,
    )

    private fun shutterAction(description: String) = selectors.action(
        resourceId = "$PIXEL_CAMERA_PACKAGE:id/shutter_button",
        contentDescription = description,
        minimumScore = 170,
    )

    private fun modeState(mode: String) = selectors.state(
        resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
        contentDescription = mode,
        text = mode,
        expectedSelected = true,
        minimumScore = 205,
    )

    private fun speedAction(speed: TimeLapseSpeed): UiSelectorSet {
        val multiplier = requireNotNull(speedMultipliers[speed])
        return selectors.action(
            contentDescription = "Time Lapse $multiplier times speed",
            text = "$multiplier×",
            minimumScore = 100,
        )
    }

    private fun speedState(description: String, text: String) = selectors.state(
        contentDescription = description,
        text = text,
        expectedSelected = true,
        minimumScore = 105,
    )

}

private class Pixel7SelectorFactory {
    fun action(
        resourceId: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        minimumScore: Int,
        requiresClickable: Boolean = true,
    ) = set(resourceId, contentDescription, text, null, minimumScore, requiresClickable)

    fun state(
        resourceId: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        expectedSelected: Boolean? = null,
        minimumScore: Int,
    ) = set(resourceId, contentDescription, text, expectedSelected, minimumScore, false)

    fun node(
        resourceId: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        expectedSelected: Boolean? = null,
        requiresClickable: Boolean = true,
    ) = UiSelector(
        packageName = PIXEL_CAMERA_PACKAGE,
        resourceId = resourceId,
        contentDescription = contentDescription,
        text = text,
        expectedSelected = expectedSelected,
        requiresClickable = requiresClickable,
    )

    private fun set(
        resourceId: String?,
        contentDescription: String?,
        text: String?,
        expectedSelected: Boolean?,
        minimumScore: Int,
        requiresClickable: Boolean,
    ) = UiSelectorSet(
        selectors = listOf(
            node(resourceId, contentDescription, text, expectedSelected, requiresClickable),
        ),
        minimumScore = minimumScore,
    )
}
