package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KnownPixelCameraProfileCatalogTest {
    private val profile = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630

    @Test
    fun `returns candidate only for the exact calibrated environment`() {
        assertSame(profile, KnownPixelCameraProfileCatalog.exactMatch(profile.environment))
        val mismatches = listOf(
            profile.environment.copy(deviceManufacturer = "Another"),
            profile.environment.copy(deviceModel = "Pixel 9 Pro"),
            profile.environment.copy(androidSdk = 36),
            profile.environment.copy(androidBuildFingerprint = "google/husky/another-build"),
            profile.environment.copy(cameraPackage = "example.camera"),
            profile.environment.copy(cameraVersionCode = profile.environment.cameraVersionCode + 1),
            profile.environment.copy(localeTag = "en-US"),
            profile.environment.copy(displayWidthPx = profile.environment.displayWidthPx + 1),
            profile.environment.copy(displayHeightPx = profile.environment.displayHeightPx + 1),
            profile.environment.copy(densityDpi = profile.environment.densityDpi + 1),
        )

        mismatches.forEach { assertNull(KnownPixelCameraProfileCatalog.exactMatch(it)) }
    }

    @Test
    fun `candidate identity and environment are stable and fail closed`() {
        assertEquals(
            "google-pixel-8-pro-sdk37-cp2a-260705-006-camera-69481630-1008x2244-en-us-v2",
            profile.id.value,
        )
        assertEquals("Google", profile.environment.deviceManufacturer)
        assertEquals("Pixel 8 Pro", profile.environment.deviceModel)
        assertEquals(37, profile.environment.androidSdk)
        assertEquals(
            "google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys",
            profile.environment.androidBuildFingerprint,
        )
        assertEquals("com.google.android.GoogleCamera", profile.environment.cameraPackage)
        assertEquals(69_481_630L, profile.environment.cameraVersionCode)
        assertEquals("en-US-u-fw-mon-mu-celsius", profile.environment.localeTag)
        assertEquals(1_008, profile.environment.displayWidthPx)
        assertEquals(2_244, profile.environment.displayHeightPx)
        assertEquals(360, profile.environment.densityDpi)
        assertEquals(PixelCameraSelectorSchema.CURRENT_VERSION, profile.selectorSchemaVersion)
        assertEquals(ProfileCompatibility.NEEDS_REHEARSAL, profile.compatibility)
        assertNull(profile.verifiedAt)
    }

    @Test
    fun `candidate contains all production actions and required observable signals`() {
        assertEquals(
            setOf(
                AutomationAction.SELECT_VIDEO,
                AutomationAction.SELECT_TIME_LAPSE,
                AutomationAction.SELECT_REAR_MAIN_LENS,
                AutomationAction.START_RECORDING,
                AutomationAction.STOP_RECORDING,
            ),
            profile.targets.keys,
        )
        assertEquals(setOf(TimeLapseSpeed.X120), profile.speedTargets.keys)
        assertEquals(
            setOf(
                PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ),
            profile.stateSignals.keys,
        )
    }

    @Test
    fun `selectors retain empirically observed semantic discriminants`() {
        val video = profile.targets.getValue(AutomationAction.SELECT_VIDEO).selectors.single()
        assertEquals("video_supermode", video.resourceId)
        assertNull(video.contentDescription)

        val timeLapse = profile.targets.getValue(AutomationAction.SELECT_TIME_LAPSE)
        assertEquals(2, timeLapse.selectors.size)
        assertTrue(timeLapse.selectors.all {
            it.resourceId == "com.google.android.GoogleCamera:id/mode_chip_text" &&
                it.text == "Time Lapse" &&
                !it.requiresClickable
        })
        assertEquals(
            setOf("Switch to Time Lapse Mode", "Time Lapse"),
            timeLapse.selectors.mapTo(linkedSetOf()) { it.contentDescription },
        )

        val speed = profile.speedTargets.getValue(TimeLapseSpeed.X120).selectors.single()
        assertEquals("Time Lapse 120 times speed", speed.contentDescription)
        assertEquals("120×", speed.text)

        val lensAction = profile.targets
            .getValue(AutomationAction.SELECT_REAR_MAIN_LENS)
            .selectors
            .single()
        assertEquals("zoom_toggle_1×", lensAction.resourceId)
        assertFalse(lensAction.requiresClickable)

        val lens = profile.stateSignals
            .getValue(PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE)
            .selectors
            .single()
        assertNull(lens.resourceId)
        assertEquals(true, lens.expectedChecked)
        assertEquals(true, lens.requiresClickable)
        assertTrue(lens.expectedRegion != null)

        val recording = profile.stateSignals
            .getValue(PixelCameraStateSignal.RECORDING_ACTIVE)
            .selectors
            .single()
        assertEquals("Stop time lapse", recording.contentDescription)
        assertEquals("ComposeShutter", recording.resourceId)

        val notRecording = profile.stateSignals
            .getValue(PixelCameraStateSignal.NOT_RECORDING)
        assertEquals(160, notRecording.minimumScore)
        assertEquals(
            setOf("Take photo", "Start video", "Start time lapse"),
            notRecording.selectors.mapTo(linkedSetOf()) { it.contentDescription },
        )

        listOf(
            PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
        ).forEach { signal ->
            assertTrue(
                profile.stateSignals.getValue(signal).selectors.single().expectedRegion != null,
                "$signal must require the centered active-mode region",
            )
        }

        val speedSignals = profile.stateSignals
            .getValue(PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE)
        assertEquals(2, speedSignals.selectors.size)
        assertEquals(40, speedSignals.minimumScore)
        assertTrue(speedSignals.selectors.any { it.expectedSelected == true })
        assertTrue(speedSignals.selectors.any { it.expectedRegion != null })
    }

    @Test
    fun `catalog definition recognizes a rehearsed copy but not selector drift`() {
        val rehearsed = profile.copy(
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-09T12:00:00Z"),
        )
        val changedSelector = profile.copy(targets = profile.targets - AutomationAction.STOP_RECORDING)
        val incompatible = profile.copy(compatibility = ProfileCompatibility.INCOMPATIBLE)

        assertTrue(KnownPixelCameraProfileCatalog.containsDefinition(rehearsed))
        assertFalse(KnownPixelCameraProfileCatalog.containsDefinition(changedSelector))
        assertFalse(KnownPixelCameraProfileCatalog.containsDefinition(incompatible))
    }
}
