package dev.po4yka.lenswake.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CaptureConfigurationTest {
    @Test
    fun `zoom accepts a finite positive factor`() {
        assertEquals(2.5f, Zoom.of(2.5f)?.factor)
    }

    @Test
    fun `zoom rejects factors that cannot identify a camera zoom`() {
        listOf(0f, 0.5f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { factor ->
            assertNull(Zoom.of(factor))
        }
    }

    @Test
    fun `video configuration carries the requested lens without a time lapse speed`() {
        val capture = CaptureConfiguration.Video(lens = LensSelection.FRONT)

        assertEquals(CaptureMode.VIDEO, capture.mode)
        assertEquals(LensSelection.FRONT, capture.lens)
        assertEquals(VideoResolution.UHD_4K, capture.resolution)
        assertEquals(VideoFrameRate.FPS_60, capture.frameRate)
        assertNull(capture.timeLapseSpeed)
    }

    @Test
    fun `legacy video settings are never supported by a current profile`() {
        val profile = PixelCameraProfile(
            id = ProfileId("capture-test"),
            environment = PixelCameraEnvironment(
                deviceManufacturer = "Google",
                deviceModel = "Pixel 8 Pro",
                androidSdk = 37,
                androidBuildFingerprint = "fingerprint",
                cameraPackage = "com.google.android.GoogleCamera",
                cameraVersionCode = 1,
                localeTag = "en-US",
                displayWidthPx = 1000,
                displayHeightPx = 2000,
                densityDpi = 400,
            ),
            selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = null,
        )

        assertFalse(
            profile.supports(
                CaptureConfiguration.Video(frameRate = VideoFrameRate.LEGACY_UNKNOWN),
            ),
        )
        assertFalse(
            profile.supports(
                CaptureConfiguration.Video(resolution = VideoResolution.LEGACY_UNKNOWN),
            ),
        )
    }

    @Test
    fun `time lapse configuration carries the requested speed and lens`() {
        val capture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X5,
            lens = LensSelection.REAR_ULTRAWIDE,
        )

        assertEquals(CaptureMode.TIME_LAPSE, capture.mode)
        assertEquals(TimeLapseSpeed.X5, capture.timeLapseSpeed)
        assertEquals(LensSelection.REAR_ULTRAWIDE, capture.lens)
    }

    @Test
    fun `night sight time lapse is a distinct configurable mode`() {
        val capture = CaptureConfiguration.NightSightTimeLapse(lens = LensSelection.REAR_MAIN)

        assertEquals(CaptureMode.NIGHT_SIGHT_TIME_LAPSE, capture.mode)
        assertNull(capture.timeLapseSpeed)
    }
}
