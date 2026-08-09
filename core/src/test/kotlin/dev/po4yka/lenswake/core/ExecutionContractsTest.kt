package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExecutionContractsTest {
    @Test
    fun `failure preserves a typed code and bounded diagnostic context`() {
        val failure = AutomationFailure(
            code = AutomationFailureCode.RECORDING_NOT_CONFIRMED,
            message = "Timer did not advance",
            context = mapOf("attempt" to "3"),
        )

        assertEquals(AutomationFailureCode.RECORDING_NOT_CONFIRMED, failure.code)
        assertEquals("3", failure.context["attempt"])
    }

    @Test
    fun `event rejects unbounded accessibility-derived metadata`() {
        val oversized = (1..AutomationEvent.MAX_METADATA_ENTRIES + 1).associate { "key$it" to "value" }

        assertThrows(IllegalArgumentException::class.java) {
            AutomationEvent(
                id = EventId.new(),
                sessionId = SessionId.new(),
                name = "automation.selector.match",
                timestamp = Instant.EPOCH,
                state = AutomationStateName.INSPECTING_CAMERA_STATE,
                outcome = AutomationOutcome.SUCCEEDED,
                metadata = oversized,
            )
        }
    }
}
