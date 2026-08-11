package dev.po4yka.lenswake.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        assertNull(capture.timeLapseSpeed)
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
