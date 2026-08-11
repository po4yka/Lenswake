package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.AlarmTransportIncidentUiAction
import dev.po4yka.lenswake.ui.DiagnosticSessionUiState
import dev.po4yka.lenswake.ui.DiagnosticTimelineEventUiState
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SectionHeading
import dev.po4yka.lenswake.ui.component.SummaryCard
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding

@Composable
fun DiagnosticsScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onOpenPixelCamera: () -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scaffoldContentViewport(contentPadding),
        contentPadding = screenContentPadding(
            topMargin = 24.dp,
            bottomMargin = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        diagnosticsHeader()
        exportAction(state, onExportDiagnostics)
        attentionItems(state, onOpenPixelCamera)
        diagnosticSessions(state)
    }
}

private fun LazyListScope.exportAction(
    state: LenswakeUiState,
    onExportDiagnostics: () -> Unit,
) {
    if (!state.actions.canExportDiagnostics) return

    item {
        OutlinedButton(
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            onClick = onExportDiagnostics,
        ) {
            Text(stringResource(R.string.action_export_diagnostics))
        }
    }
}

private fun LazyListScope.diagnosticsHeader() {
    item {
        ScreenHeader(
            title = stringResource(R.string.nav_diagnostics),
            summary = stringResource(R.string.screen_diagnostics_summary),
        )
    }
}

private fun LazyListScope.attentionItems(
    state: LenswakeUiState,
    onOpenPixelCamera: () -> Unit,
) {
    val hasAttentionItems = state.alarmTransportIncidents.isNotEmpty() ||
        state.profilePersistenceIssues.isNotEmpty()
    if (!hasAttentionItems) return

    item { SectionHeading(stringResource(R.string.status_needs_attention)) }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.alarmTransportIncidents.forEach { incident ->
                val opensPixelCamera = incident.action == AlarmTransportIncidentUiAction.OPEN_PIXEL_CAMERA
                SummaryCard(
                    title = incident.title,
                    detail = stringResource(
                        R.string.diagnostics_timed_detail,
                        incident.occurredAt,
                        incident.detail,
                    ),
                    status = stringResource(R.string.status_needs_attention),
                    actionLabel = if (opensPixelCamera) {
                        stringResource(R.string.action_open_pixel_camera)
                    } else {
                        null
                    },
                    onAction = onOpenPixelCamera.takeIf { opensPixelCamera },
                )
            }
            state.profilePersistenceIssues.forEach { issue ->
                SummaryCard(
                    title = issue.title,
                    detail = issue.detail,
                    status = stringResource(R.string.status_needs_attention),
                )
            }
        }
    }
}

private fun LazyListScope.diagnosticSessions(state: LenswakeUiState) {
    item { SectionHeading(stringResource(R.string.diagnostics_sessions_section)) }
    if (state.diagnosticSessions.isEmpty()) {
        item {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.diagnostics_no_activity_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.diagnostics_no_activity_detail))
                },
            )
        }
        return
    }
    state.diagnosticSessions.forEach { session ->
        item(key = "diagnostic-session-${session.id}") {
            DiagnosticSessionCard(session)
        }
        session.timeline.forEach { event ->
            item(key = "diagnostic-event-${session.id}-${event.id}") {
                DiagnosticTimelineRow(
                    event = event,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticSessionCard(session: DiagnosticSessionUiState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(session.title, style = MaterialTheme.typography.titleMedium)
                        Text(session.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        session.status,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    stringResource(R.string.diagnostics_session_duration, session.duration),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SessionMetrics(session)
                Text(
                    stringResource(R.string.diagnostics_timeline_section),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun SessionMetrics(session: DiagnosticSessionUiState) {
    val metrics = session.metrics
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.diagnostics_retry_count, metrics.retryCount),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                stringResource(R.string.diagnostics_fallback_count, metrics.fallbackCount),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(
                    R.string.diagnostics_privileged_fallback_count,
                    metrics.privilegedFallbackCount,
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                metrics.selectorConfidence?.let { confidence ->
                    stringResource(
                        R.string.diagnostics_selector_confidence,
                        confidence.score,
                        confidence.minimumScore,
                    )
                } ?: stringResource(R.string.diagnostics_selector_confidence_unavailable),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun DiagnosticTimelineRow(
    event: DiagnosticTimelineEventUiState,
    modifier: Modifier = Modifier,
) {
    val separator = stringResource(R.string.diagnostics_event_metrics_separator)
    val metrics = buildList {
        event.duration?.let { add(stringResource(R.string.diagnostics_event_duration, it)) }
        event.interactionMethod?.let { add(stringResource(R.string.diagnostics_event_method, it)) }
        event.attempt?.let { add(stringResource(R.string.diagnostics_event_attempt, it)) }
        event.selectorMatch?.let(::add)
        event.selectorConfidence?.let { confidence ->
            add(
                stringResource(
                    R.string.diagnostics_selector_confidence,
                    confidence.score,
                    confidence.minimumScore,
                ),
            )
        }
    }
    ListItem(
        modifier = modifier,
        headlineContent = { Text(event.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.diagnostics_timed_detail, event.occurredAt, event.detail))
                if (metrics.isNotEmpty()) {
                    Text(metrics.joinToString(separator), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
    )
}
