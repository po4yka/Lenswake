package dev.po4yka.lenswake.ui

internal object DiagnosticsExportFormatter {
    fun format(
        state: LenswakeUiState,
        strings: UiStringProvider,
    ): String? {
        val hasAttentionItems = state.alarmTransportIncidents.isNotEmpty() ||
            state.profilePersistenceIssues.isNotEmpty()
        if (!hasAttentionItems && state.diagnosticEvents.isEmpty()) return null

        return buildString {
            appendLine(strings.get(dev.po4yka.lenswake.R.string.diagnostics_export_title))
            if (hasAttentionItems) {
                appendLine()
                appendLine(strings.get(dev.po4yka.lenswake.R.string.status_needs_attention))
                state.alarmTransportIncidents.forEach { incident ->
                    appendDiagnosticItem(incident.occurredAt, incident.title, incident.detail)
                }
                state.profilePersistenceIssues.forEach { issue ->
                    appendDiagnosticItem(timestamp = null, issue.title, issue.detail)
                }
            }
            if (state.diagnosticEvents.isNotEmpty()) {
                appendLine()
                appendLine(strings.get(dev.po4yka.lenswake.R.string.section_activity))
                state.diagnosticEvents.forEach { event ->
                    appendDiagnosticItem(event.occurredAt, event.title, event.detail)
                }
            }
        }.trimEnd()
    }
}

private fun StringBuilder.appendDiagnosticItem(
    timestamp: String?,
    title: String,
    detail: String,
) {
    append("- ")
    if (!timestamp.isNullOrBlank()) {
        append(timestamp)
        append(" · ")
    }
    appendLine(title)
    append("  ")
    appendLine(detail)
}
