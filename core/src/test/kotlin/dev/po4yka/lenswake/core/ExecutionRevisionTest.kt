package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExecutionRevisionTest {
    private val pending = ExecutionSession(
        id = SessionId.new(),
        executionKey = "schedule/sunrise/1786325400000",
        kind = SessionKind.SCHEDULED,
        scheduleId = ScheduleId.new(),
        scheduleName = "Sunrise",
        profileId = ProfileId.new(),
        capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
        expectedStartAt = Instant.parse("2026-08-10T01:30:00Z"),
        expectedStopAt = Instant.parse("2026-08-10T03:30:00Z"),
        status = SessionStatus.PENDING,
        createdAt = Instant.parse("2026-08-09T10:00:00Z"),
        updatedAt = Instant.parse("2026-08-09T10:00:00Z"),
    )

    @Test
    fun `execution change advances exactly one revision`() {
        val updated = pending.copy(
            status = SessionStatus.STARTING,
            currentAutomationState = AutomationStateName.START_TRIGGERED,
            revision = 1,
        )

        val change = ExecutionChange(expectedRevision = 0, updatedSession = updated)

        assertEquals(0, change.expectedRevision)
        assertEquals(1, change.updatedSession.revision)
    }

    @Test
    fun `execution change rejects skipped revision`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionChange(
                expectedRevision = 0,
                updatedSession = pending.copy(revision = 2),
            )
        }
    }
}
