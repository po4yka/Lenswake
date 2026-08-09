package dev.po4yka.lenswake.core

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RehearsalRequestTest {
    private val profileId = ProfileId("pixel-8-pro-profile")
    private val capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120)

    @Test
    fun `recording duration accepts inclusive boundaries at millisecond precision`() {
        listOf(
            Duration.ofSeconds(5),
            Duration.ofMillis(5_001),
            Duration.ofSeconds(30),
        ).forEach { duration ->
            assertDoesNotThrow {
                RehearsalRequest(profileId, capture, duration)
            }
        }
    }

    @Test
    fun `recording duration rejects out of range sub-millisecond and overflowing values`() {
        listOf(
            Duration.ofMillis(4_999),
            Duration.ofMillis(30_001),
            Duration.ofSeconds(5).plusNanos(1),
            Duration.ofSeconds(Long.MAX_VALUE),
        ).forEach { duration ->
            assertThrows(IllegalArgumentException::class.java) {
                RehearsalRequest(profileId, capture, duration)
            }
        }
    }
}
