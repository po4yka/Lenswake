package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PixelCameraProfileTest {
    @Test
    fun `profile carries data-driven observation signals`() {
        val recordingSelector = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = "com.google.android.GoogleCamera",
                    role = "android.widget.Button",
                    expectedSelected = true,
                ),
            ),
            minimumScore = 30,
        )
        val profile = PixelCameraProfile(
            id = ProfileId("profile-1"),
            environment = environment(),
            selectorSchemaVersion = 1,
            speedTargets = mapOf(TimeLapseSpeed.X30 to recordingSelector),
            stateSignals = mapOf(PixelCameraStateSignal.RECORDING_ACTIVE to recordingSelector),
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = null,
        )

        assertEquals(recordingSelector, profile.speedTargets[TimeLapseSpeed.X30])
        assertEquals(recordingSelector, profile.stateSignals[PixelCameraStateSignal.RECORDING_ACTIVE])
        assertEquals(true, recordingSelector.selectors.single().expectedSelected)
    }

    @Test
    fun `selector rejects a blank package boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            UiSelector(packageName = " ")
        }
    }

    @Test
    fun `profile rejects selectors outside its calibrated camera package`() {
        val foreignSelector = UiSelectorSet(
            selectors = listOf(UiSelector(packageName = "example.other.camera")),
            minimumScore = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PixelCameraProfile(
                id = ProfileId("profile-1"),
                environment = environment(),
                selectorSchemaVersion = 1,
                stateSignals = mapOf(PixelCameraStateSignal.NOT_RECORDING to foreignSelector),
                compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
                verifiedAt = null,
            )
        }
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
