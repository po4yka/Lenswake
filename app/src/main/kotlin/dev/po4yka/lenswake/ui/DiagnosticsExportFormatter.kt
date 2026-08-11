package dev.po4yka.lenswake.ui

internal object DiagnosticsExportFormatter {
    fun format(
        state: LenswakeUiState,
        strings: UiStringProvider,
    ): String? {
        val hasAttentionItems = state.alarmTransportIncidents.isNotEmpty() ||
            state.profilePersistenceIssues.isNotEmpty()
        if (!hasAttentionItems && state.diagnosticSessions.isEmpty()) return null

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
            if (state.diagnosticSessions.isNotEmpty()) {
                appendLine()
                appendLine(strings.get(dev.po4yka.lenswake.R.string.diagnostics_sessions_section))
                state.diagnosticSessions.forEach { session ->
                    appendLine("${session.title} · ${session.status} · ${session.duration}")
                    appendLine("  ${session.detail}")
                    appendLine("  ${session.metrics.summary(strings)}")
                    appendLine("  ${strings.get(dev.po4yka.lenswake.R.string.diagnostics_timeline_section)}")
                    session.timeline.forEach { event ->
                        appendDiagnosticItem(event.occurredAt, event.title, event.detail, indent = "  ")
                        val eventMetrics = event.metricsSummary(strings)
                        if (eventMetrics.isNotEmpty()) appendLine("    $eventMetrics")
                    }
                }
            }
        }.trimEnd()
    }
}

private fun StringBuilder.appendDiagnosticItem(
    timestamp: String?,
    title: String,
    detail: String,
    indent: String = "",
) {
    append(indent)
    append("- ")
    if (!timestamp.isNullOrBlank()) {
        append(timestamp)
        append(" · ")
    }
    appendLine(title)
    append(indent)
    append("  ")
    appendLine(detail)
}

private fun DiagnosticSessionMetricsUiState.summary(strings: UiStringProvider): String = listOf(
    strings.get(dev.po4yka.lenswake.R.string.diagnostics_retry_count, retryCount),
    strings.get(dev.po4yka.lenswake.R.string.diagnostics_fallback_count, fallbackCount),
    strings.get(dev.po4yka.lenswake.R.string.diagnostics_privileged_fallback_count, privilegedFallbackCount),
    selectorConfidence?.let { confidence ->
        strings.get(
            dev.po4yka.lenswake.R.string.diagnostics_selector_confidence,
            confidence.score,
            confidence.minimumScore,
        )
    } ?: strings.get(dev.po4yka.lenswake.R.string.diagnostics_selector_confidence_unavailable),
).joinToString(strings.get(dev.po4yka.lenswake.R.string.diagnostics_event_metrics_separator))

private fun DiagnosticTimelineEventUiState.metricsSummary(strings: UiStringProvider): String = buildList {
    duration?.let { add(strings.get(dev.po4yka.lenswake.R.string.diagnostics_event_duration, it)) }
    interactionMethod?.let { add(strings.get(dev.po4yka.lenswake.R.string.diagnostics_event_method, it)) }
    attempt?.let { add(strings.get(dev.po4yka.lenswake.R.string.diagnostics_event_attempt, it)) }
    selectorMatch?.let(::add)
    selectorConfidence?.let { confidence ->
        add(
            strings.get(
                dev.po4yka.lenswake.R.string.diagnostics_selector_confidence,
                confidence.score,
                confidence.minimumScore,
            ),
        )
    }
}.joinToString(strings.get(dev.po4yka.lenswake.R.string.diagnostics_event_metrics_separator))
