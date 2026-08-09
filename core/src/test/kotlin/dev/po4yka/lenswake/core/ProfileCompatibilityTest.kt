package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileCompatibilityTest {
    private val calibrated = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        androidSdk = 37,
        androidBuildFingerprint = "google/husky/build-a",
        cameraPackage = "com.google.android.GoogleCamera",
        cameraVersionCode = 700_000_000L,
        localeTag = "en-US",
        displayWidthPx = 1344,
        displayHeightPx = 2992,
        densityDpi = 480,
    )

    @Test
    fun `exact environment match is verified`() {
        assertEquals(
            ProfileCompatibility.VERIFIED,
            ProfileCompatibilityEvaluator.evaluate(calibrated, calibrated),
        )
    }

    @Test
    fun `camera update requires rehearsal on the same device`() {
        assertEquals(
            ProfileCompatibility.NEEDS_REHEARSAL,
            ProfileCompatibilityEvaluator.evaluate(
                calibrated,
                calibrated.copy(cameraVersionCode = calibrated.cameraVersionCode + 1),
            ),
        )
    }

    @Test
    fun `profile never crosses a device model boundary implicitly`() {
        assertEquals(
            ProfileCompatibility.INCOMPATIBLE,
            ProfileCompatibilityEvaluator.evaluate(calibrated, calibrated.copy(deviceModel = "Pixel 9 Pro")),
        )
    }

    @Test
    fun `stored profile status cannot be upgraded by an environment match`() {
        val profile = PixelCameraProfile(
            id = ProfileId.new(),
            environment = calibrated,
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = Instant.parse("2026-08-09T10:00:00Z"),
        )

        assertEquals(ProfileCompatibility.NEEDS_REHEARSAL, profile.compatibilityFor(calibrated))
    }
}
