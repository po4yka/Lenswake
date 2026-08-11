package dev.po4yka.lenswake.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DiagnosticsExportFormatterTest {
    @Test
    fun `empty diagnostics do not produce an export`() {
        assertNull(DiagnosticsExportFormatter.format(LenswakeUiState(), TestUiStringProvider))
    }

    @Test
    fun `export contains attention items and activity in visible order`() {
        val state = LenswakeUiState(
            alarmTransportIncidents = listOf(
                AlarmTransportIncidentUiState(
                    id = "alarm-1",
                    title = "Scheduled STOP needs manual action",
                    detail = "Open Pixel Camera and stop recording.",
                    occurredAt = "08:30",
                ),
            ),
            profilePersistenceIssues = listOf(
                ProfilePersistenceIssueUiState(
                    id = "profile-1",
                    title = "Camera profile storage issue",
                    detail = "A stored profile could not be read.",
                ),
            ),
            diagnosticEvents = listOf(
                DiagnosticEventUiState(
                    id = "event-1",
                    title = "automation.record.stop_verified",
                    detail = "Completed",
                    occurredAt = "08:31",
                ),
            ),
        )

        assertEquals(
            """
            Lenswake diagnostics

            Needs attention
            - 08:30 · Scheduled STOP needs manual action
              Open Pixel Camera and stop recording.
            - Camera profile storage issue
              A stored profile could not be read.

            Activity
            - 08:31 · automation.record.stop_verified
              Completed
            """.trimIndent(),
            DiagnosticsExportFormatter.format(state, TestUiStringProvider),
        )
    }
}
