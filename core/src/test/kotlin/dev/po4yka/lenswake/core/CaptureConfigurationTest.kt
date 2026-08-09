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
}
