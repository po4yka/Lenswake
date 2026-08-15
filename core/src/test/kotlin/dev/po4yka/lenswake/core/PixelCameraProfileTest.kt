package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class PixelCameraProfileTest {
    @Test
    fun `video contract changes definition fingerprint and support`() {
        val profile = PixelCameraProfile(
            id = ProfileId("video-contract"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            videoSettings = PIXEL_CAMERA_VIDEO_SETTINGS,
            targets = videoTargets(),
            stateSignals = videoSignals(),
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = null,
        )

        assertEquals(true, profile.supports(CaptureConfiguration.Video()))
        assertEquals(
            false,
            profile.copy(videoSettings = LEGACY_UNKNOWN_VIDEO_SETTINGS)
                .supports(CaptureConfiguration.Video()),
        )
        assertNotEquals(
            profile.definitionFingerprint(),
            profile.copy(videoSettings = LEGACY_UNKNOWN_VIDEO_SETTINGS).definitionFingerprint(),
        )
    }

    @Test
    fun `certified tier requires immutable release evidence and changes definition fingerprint`() {
        val experimental = PixelCameraProfile(
            id = ProfileId("certification-target"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            experimental.copy(supportTier = SupportTier.CERTIFIED)
        }

        val certified = experimental.copy(
            supportTier = SupportTier.CERTIFIED,
            certification = certification(),
        )

        assertEquals(SupportTier.CERTIFIED, certified.supportTier)
        assertNotEquals(experimental.definitionFingerprint(), certified.definitionFingerprint())
        assertNotEquals(
            certified.definitionFingerprint(),
            certified.copy(certification = certification().copy(bundleSha256 = "7".repeat(64)))
                .definitionFingerprint(),
        )
    }
    @Test
    fun `support tier is independent from local rehearsal state`() {
        val profile = PixelCameraProfile(
            id = ProfileId("experimental-profile"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            supportTier = SupportTier.EXPERIMENTAL,
            selectorTemplate = SelectorTemplateReference("pixel-7-semantic", 1),
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-12T00:00:00Z"),
        )

        assertEquals(SupportTier.EXPERIMENTAL, profile.supportTier)
    }

    private fun certification() = ProfileCertification(
        releaseTag = "v1.2.3",
        releaseCommit = "1".repeat(40),
        candidateRunId = 123,
        lenswakeApkSha256 = "2".repeat(64),
        bundleSha256 = "3".repeat(64),
        pixel7EvidenceSha256 = "4".repeat(64),
        pixel8ProEvidenceSha256 = "5".repeat(64),
    )

    private fun videoTargets(): Map<AutomationAction, UiSelectorSet> = listOf(
        AutomationAction.SELECT_VIDEO,
        AutomationAction.SELECT_VIDEO_RESOLUTION_4K,
        AutomationAction.SELECT_VIDEO_FRAME_RATE_60,
        AutomationAction.SELECT_REAR_MAIN_LENS,
        AutomationAction.START_VIDEO_RECORDING,
        AutomationAction.STOP_VIDEO_RECORDING,
    ).associateWith {
        UiSelectorSet(listOf(UiSelector("com.google.android.GoogleCamera", role = "Button")), 1)
    }

    private fun videoSignals(): Map<PixelCameraStateSignal, UiSelectorSet> = listOf(
        PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
        PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
        PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE,
        PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE,
        PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
        PixelCameraStateSignal.RECORDING_ACTIVE,
        PixelCameraStateSignal.NOT_RECORDING,
    ).associateWith {
        UiSelectorSet(listOf(UiSelector("com.google.android.GoogleCamera", role = "Button")), 1)
    }

    @Test
    fun `profile carries data-driven observation signals`() {
        val recordingSelector = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = "com.google.android.GoogleCamera",
                    role = "android.widget.Button",
                    expectedSelected = true,
                    expectedChecked = true,
                ),
            ),
            minimumScore = 30,
        )
        val profile = PixelCameraProfile(
            id = ProfileId("profile-1"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            speedTargets = mapOf(TimeLapseSpeed.X30 to recordingSelector),
            stateSignals = mapOf(PixelCameraStateSignal.RECORDING_ACTIVE to recordingSelector),
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = null,
        )

        assertEquals(recordingSelector, profile.speedTargets[TimeLapseSpeed.X30])
        assertEquals(recordingSelector, profile.stateSignals[PixelCameraStateSignal.RECORDING_ACTIVE])
        assertEquals(true, recordingSelector.selectors.single().expectedSelected)
        assertEquals(true, recordingSelector.selectors.single().expectedChecked)
    }

    @Test
    fun `selector rejects a blank package boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            UiSelector(packageName = " ")
        }
    }

    @Test
    fun `verified profile requires verification timestamp`() {
        assertThrows(IllegalArgumentException::class.java) {
            PixelCameraProfile(
                id = ProfileId("profile-1"),
                environment = environment(),
                selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
                compatibility = ProfileCompatibility.VERIFIED,
                verifiedAt = null,
            )
        }
    }

    @Test
    fun `unsupported selector schema is fail closed`() {
        val profile = PixelCameraProfile(
            id = ProfileId("profile-1"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION + 1,
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-09T10:00:00Z"),
        )

        assertEquals(ProfileCompatibility.INCOMPATIBLE, profile.compatibilityFor(environment()))
    }

    @Test
    fun `profile from previous selector schema remains incompatible`() {
        val legacy = PixelCameraProfile(
            id = ProfileId("legacy-profile"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION - 1,
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-09T10:00:00Z"),
        )

        assertEquals(ProfileCompatibility.INCOMPATIBLE, legacy.compatibilityFor(environment()))
    }

    @Test
    fun `profile rejects selectors outside its calibrated camera package`() {
        val foreignSelector = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = "example.other.camera",
                    role = "android.widget.Button",
                ),
            ),
            minimumScore = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PixelCameraProfile(
                id = ProfileId("profile-1"),
                environment = environment(),
                selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
                stateSignals = mapOf(PixelCameraStateSignal.NOT_RECORDING to foreignSelector),
                compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
                verifiedAt = null,
            )
        }
    }

    @Test
    fun `profile rejects action selector with only package and service state`() {
        val unsafeSelector = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = environment().cameraPackage,
                    expectedSelected = true,
                    expectedChecked = true,
                    requiresClickable = true,
                ),
            ),
            minimumScore = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PixelCameraProfile(
                id = ProfileId("profile-1"),
                environment = environment(),
                selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
                targets = mapOf(AutomationAction.START_RECORDING to unsafeSelector),
                compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
                verifiedAt = null,
            )
        }
    }

    @Test
    fun `unknown dialog cannot be configured with an automatic recovery target`() {
        val selector = UiSelectorSet(
            selectors = listOf(UiSelector(environment().cameraPackage, text = "Unknown dialog")),
            minimumScore = 30,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PixelCameraProfile(
                id = ProfileId("profile-1"),
                environment = environment(),
                selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
                dialogProfiles = mapOf(
                    PixelCameraDialogKind.UNKNOWN to PixelCameraDialogProfile(selector, selector),
                ),
                compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
                verifiedAt = null,
            )
        }
    }

    @Test
    fun `dialog presence selector requires a meaningful discriminant`() {
        val unsafePresence = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = environment().cameraPackage,
                    expectedSelected = true,
                    requiresClickable = false,
                ),
            ),
            minimumScore = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PixelCameraProfile(
                id = ProfileId("profile-1"),
                environment = environment(),
                selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
                dialogProfiles = mapOf(
                    PixelCameraDialogKind.UNKNOWN to PixelCameraDialogProfile(
                        presence = unsafePresence,
                        recoveryTarget = null,
                    ),
                ),
                compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
                verifiedAt = null,
            )
        }
    }

    @Test
    fun `region is a meaningful action selector discriminant`() {
        val regionSelector = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = environment().cameraPackage,
                    expectedRegion = NormalizedBounds(0.25f, 0.25f, 0.75f, 0.75f),
                ),
            ),
            minimumScore = 10,
        )

        val profile = PixelCameraProfile(
            id = ProfileId("profile-1"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            speedTargets = mapOf(TimeLapseSpeed.X120 to regionSelector),
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = null,
        )

        assertEquals(regionSelector, profile.speedTargets[TimeLapseSpeed.X120])
    }

    @Test
    fun `capture support is derived from calibrated mode speed and lens selectors`() {
        val selector = UiSelectorSet(
            selectors = listOf(UiSelector(environment().cameraPackage, text = "verified")),
            minimumScore = 10,
        )
        val profile = PixelCameraProfile(
            id = ProfileId("profile-capabilities"),
            environment = environment(),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            targets = setOf(
                AutomationAction.SELECT_VIDEO,
                AutomationAction.SELECT_TIME_LAPSE,
                AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL,
                AutomationAction.SELECT_REAR_TELEPHOTO_LENS,
                AutomationAction.START_RECORDING,
                AutomationAction.STOP_RECORDING,
            ).associateWith { selector },
            speedTargets = mapOf(TimeLapseSpeed.X30 to selector),
            stateSignals = setOf(
                PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X30_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
                PixelCameraStateSignal.REAR_TELEPHOTO_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ).associateWith { selector },
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = null,
        )

        assertEquals(
            setOf(CaptureConfiguration.TimeLapse(TimeLapseSpeed.X30, LensSelection.REAR_TELEPHOTO)),
            profile.supportedCaptureConfigurations(),
        )
        assertEquals(
            false,
            profile.supports(CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120, LensSelection.REAR_MAIN)),
        )
    }

    private fun environment(): PixelCameraEnvironment = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        androidSdk = 37,
        androidBuildFingerprint = "google/husky/test",
        cameraPackage = "com.google.android.GoogleCamera",
        cameraVersionCode = 700_000_000,
        localeTag = "en-US",
        displayWidthPx = 1_344,
        displayHeightPx = 2_992,
        densityDpi = 480,
    )
}
