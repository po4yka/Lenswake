package dev.po4yka.lenswake.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class IdentifiersTest {
    @Test
    fun `identifiers preserve their UUID and generate unique values`() {
        val value = "5598bf0d-707b-4e7c-a3c3-7b785bf6097b"

        assertEquals(value, ScheduleId(value).value)
        assertNotEquals(SessionId.new(), SessionId.new())
    }
}
