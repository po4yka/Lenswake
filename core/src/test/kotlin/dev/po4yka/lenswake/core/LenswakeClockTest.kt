package dev.po4yka.lenswake.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LenswakeClockTest {
    @Test
    fun `system clock adapter honors its injected java clock`() {
        val expected = Instant.parse("2026-08-09T10:15:30Z")
        val clock = SystemLenswakeClock(Clock.fixed(expected, ZoneOffset.UTC))

        assertEquals(expected, clock.now())
    }
}
