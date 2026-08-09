package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet

/**
 * Profiles backed by a reproducible physical-device calibration.
 *
 * A catalog entry is offered only for an exact environment identity. Installing it does not make
 * it verified: the production automation stack must still complete a rehearsal on that device.
 */
object KnownPixelCameraProfileCatalog {
    val pixel8ProAndroid17Camera69481630: PixelCameraProfile = PixelCameraProfile(
        id = ProfileId(
            "google-pixel-8-pro-sdk37-cp2a-260705-006-camera-69481630-1008x2244-en-us-v2",
        ),
        environment = PixelCameraEnvironment(
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            androidSdk = 37,
            androidBuildFingerprint =
                "google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys",
            cameraPackage = PIXEL_CAMERA_PACKAGE,
            cameraVersionCode = 69_481_630L,
            localeTag = "en-US-u-fw-mon-mu-celsius",
            displayWidthPx = 1_008,
            displayHeightPx = 2_244,
            densityDpi = 360,
        ),
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        targets = mapOf(
            AutomationAction.SELECT_VIDEO to actionSelector(
                resourceId = "video_supermode",
                minimumScore = 110,
            ),
            AutomationAction.SELECT_TIME_LAPSE to actionSelector(
                resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                contentDescription = "Switch to Time Lapse Mode",
                text = "Time Lapse",
                minimumScore = 190,
                requiresClickable = false,
            ),
            AutomationAction.SELECT_REAR_MAIN_LENS to actionSelector(
                resourceId = "zoom_toggle_1×",
                text = "1×",
                minimumScore = 130,
                requiresClickable = false,
            ),
            AutomationAction.START_RECORDING to actionSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Start time lapse",
                minimumScore = 170,
            ),
            AutomationAction.STOP_RECORDING to actionSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Stop time lapse",
                minimumScore = 170,
            ),
        ),
        speedTargets = mapOf(
            TimeLapseSpeed.X120 to actionSelector(
                contentDescription = "Time Lapse 120 times speed",
                text = "120×",
                minimumScore = 100,
            ),
        ),
        stateSignals = mapOf(
            PixelCameraStateSignal.PHOTO_MODE_ACTIVE to stateSelector(
                resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                text = "Photo",
                expectedSelected = true,
                minimumScore = 145,
            ),
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE to stateSelector(
                resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                text = "Video",
                expectedSelected = true,
                minimumScore = 145,
            ),
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE to stateSelector(
                resourceId = "$PIXEL_CAMERA_PACKAGE:id/mode_chip_text",
                contentDescription = "Time Lapse",
                text = "Time Lapse",
                expectedSelected = true,
                minimumScore = 205,
            ),
            PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE to UiSelectorSet(
                selectors = listOf(
                    UiSelector(
                        packageName = PIXEL_CAMERA_PACKAGE,
                        contentDescription = "Time Lapse 120 times speed",
                        text = "120×",
                        expectedSelected = true,
                        requiresClickable = false,
                    ),
                    UiSelector(
                        packageName = PIXEL_CAMERA_PACKAGE,
                        text = "120×",
                        expectedRegion = NormalizedBounds(0.65f, 0.80f, 1f, 1f),
                        requiresClickable = false,
                    ),
                ),
                minimumScore = 40,
            ),
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE to stateSelector(
                expectedChecked = true,
                expectedRegion = NormalizedBounds(0.40f, 0.60f, 0.50f, 0.68f),
                minimumScore = 35,
                requiresClickable = true,
            ),
            PixelCameraStateSignal.RECORDING_ACTIVE to stateSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Stop time lapse",
                minimumScore = 160,
            ),
            PixelCameraStateSignal.NOT_RECORDING to stateSelector(
                resourceId = "ComposeShutter",
                contentDescription = "Start time lapse",
                minimumScore = 160,
            ),
        ),
        compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
        verifiedAt = null,
    )

    private val profiles = listOf(pixel8ProAndroid17Camera69481630)

    fun exactMatch(environment: PixelCameraEnvironment): PixelCameraProfile? =
        profiles.singleOrNull { it.environment == environment }

    /** True when [profile] is this catalog entry, allowing rehearsal status to differ. */
    fun containsDefinition(profile: PixelCameraProfile): Boolean {
        val known = profiles.singleOrNull { it.id == profile.id } ?: return false
        val installableStatuses = setOf(
            ProfileCompatibility.NEEDS_REHEARSAL,
            ProfileCompatibility.VERIFIED,
        )
        if (profile.compatibility !in installableStatuses) {
            return false
        }
        return profile.copy(
            compatibility = known.compatibility,
            verifiedAt = known.verifiedAt,
        ) == known
    }

    private fun actionSelector(
        resourceId: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        minimumScore: Int,
        requiresClickable: Boolean = true,
    ): UiSelectorSet = selectorSet(
        resourceId = resourceId,
        contentDescription = contentDescription,
        text = text,
        minimumScore = minimumScore,
        requiresClickable = requiresClickable,
    )

    private fun stateSelector(
        resourceId: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        expectedSelected: Boolean? = null,
        expectedChecked: Boolean? = null,
        expectedRegion: NormalizedBounds? = null,
        minimumScore: Int,
        requiresClickable: Boolean = false,
    ): UiSelectorSet = selectorSet(
        resourceId = resourceId,
        contentDescription = contentDescription,
        text = text,
        expectedSelected = expectedSelected,
        expectedChecked = expectedChecked,
        expectedRegion = expectedRegion,
        minimumScore = minimumScore,
        requiresClickable = requiresClickable,
    )

    private fun selectorSet(
        resourceId: String?,
        contentDescription: String?,
        text: String?,
        expectedSelected: Boolean? = null,
        expectedChecked: Boolean? = null,
        expectedRegion: NormalizedBounds? = null,
        minimumScore: Int,
        requiresClickable: Boolean,
    ): UiSelectorSet = UiSelectorSet(
        selectors = listOf(
            UiSelector(
                packageName = PIXEL_CAMERA_PACKAGE,
                resourceId = resourceId,
                contentDescription = contentDescription,
                text = text,
                expectedSelected = expectedSelected,
                expectedChecked = expectedChecked,
                expectedRegion = expectedRegion,
                requiresClickable = requiresClickable,
            ),
        ),
        minimumScore = minimumScore,
    )

    private const val PIXEL_CAMERA_PACKAGE = "com.google.android.GoogleCamera"
}
