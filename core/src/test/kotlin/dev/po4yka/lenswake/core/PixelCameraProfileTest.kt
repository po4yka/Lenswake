package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PixelCameraProfileTest {
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
