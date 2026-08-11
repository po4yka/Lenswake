package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.AlarmTransportIncidentUiAction
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
        diagnosticActivity(state)
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

private fun LazyListScope.diagnosticActivity(state: LenswakeUiState) {
    item { SectionHeading(stringResource(R.string.section_activity)) }
    item {
        if (state.diagnosticEvents.isEmpty()) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.diagnostics_no_activity_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.diagnostics_no_activity_detail))
                },
            )
        } else {
            Column {
                state.diagnosticEvents.forEachIndexed { index, event ->
                    ListItem(
                        headlineContent = { Text(event.title) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.diagnostics_timed_detail,
                                    event.occurredAt,
                                    event.detail,
                                ),
                            )
                        },
                    )
                    if (index < state.diagnosticEvents.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
