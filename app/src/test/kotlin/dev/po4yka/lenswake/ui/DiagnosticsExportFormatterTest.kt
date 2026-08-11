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
    fun `export contains attention items and session timeline metrics in visible order`() {
        assertEquals(
            EXPECTED_EXPORT,
            DiagnosticsExportFormatter.format(diagnosticsState(), TestUiStringProvider),
        )
    }

    private fun diagnosticsState() = LenswakeUiState(
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
        diagnosticSessions = listOf(
            DiagnosticSessionUiState(
                id = "session-1",
                title = "Morning capture",
                detail = "08:00 · Session session-1",
                status = "COMPLETED",
                duration = "1m 0s",
                metrics = DiagnosticSessionMetricsUiState(
                    retryCount = 1,
                    fallbackCount = 2,
                    privilegedFallbackCount = 1,
                    selectorConfidence = DiagnosticSelectorConfidenceUiState(180, 160),
                ),
                timeline = listOf(
                    DiagnosticTimelineEventUiState(
                        id = "event-1",
                        title = "automation.record.stop_verified",
                        detail = "Completed",
                        occurredAt = "08:31",
                        duration = "2s",
                        interactionMethod = "PRIVILEGED_INPUT",
                        attempt = 2,
                        selectorConfidence = DiagnosticSelectorConfidenceUiState(180, 160),
                        selectorMatch = "Selector match: #0 (RESOURCE_ID)",
                    ),
                ),
            ),
        ),
    )

    private companion object {
        val EXPECTED_EXPORT = """
            Lenswake diagnostics

            Needs attention
            - 08:30 · Scheduled STOP needs manual action
              Open Pixel Camera and stop recording.
            - Camera profile storage issue
              A stored profile could not be read.

            Sessions
            Morning capture · COMPLETED · 1m 0s
              08:00 · Session session-1
              Retries: 1 · Gesture fallbacks: 2 · Privileged: 1 · Selector: 180 / 160
              Timeline
              - 08:31 · automation.record.stop_verified
                Completed
                Duration: 2s · Method: PRIVILEGED_INPUT · Attempt: 2 · Selector match: #0 (RESOURCE_ID) · Selector: 180 / 160
        """.trimIndent()
    }
}
