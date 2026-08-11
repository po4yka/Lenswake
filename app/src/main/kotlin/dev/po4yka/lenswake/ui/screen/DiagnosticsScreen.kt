package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.AlarmTransportIncidentUiAction
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.component.ActionSection
import dev.po4yka.lenswake.ui.component.CapabilityRow
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SectionHeading
import dev.po4yka.lenswake.ui.component.SummaryCard
import dev.po4yka.lenswake.ui.component.StatusRow
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding

@Composable
fun DiagnosticsScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onOpenPixelCamera: () -> Unit = {},
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
        capabilities(state)
        alarmTransportIncidents(state, onOpenPixelCamera)
        profilePersistenceIssues(state)
        diagnosticActivity(state)
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

private fun LazyListScope.capabilities(state: LenswakeUiState) {
    item { SectionHeading(stringResource(R.string.section_capabilities)) }
    items(state.capabilities.size, key = { state.capabilities[it].name }) { index ->
        CapabilityRow(capability = state.capabilities[index])
        if (index < state.capabilities.lastIndex) {
            HorizontalDivider()
        }
    }
}

private fun LazyListScope.alarmTransportIncidents(
    state: LenswakeUiState,
    onOpenPixelCamera: () -> Unit,
) {
    if (state.alarmTransportIncidents.isEmpty()) return

    item { SectionHeading(stringResource(R.string.section_scheduled_alarm_failures)) }
    items(
        state.alarmTransportIncidents.size,
        key = { state.alarmTransportIncidents[it].id },
    ) { index ->
        val incident = state.alarmTransportIncidents[index]
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
            onAction = if (opensPixelCamera) onOpenPixelCamera else null,
        )
    }
}

private fun LazyListScope.profilePersistenceIssues(state: LenswakeUiState) {
    if (state.profilePersistenceIssues.isEmpty()) return

    item { SectionHeading(stringResource(R.string.section_profile_storage_issues)) }
    items(
        state.profilePersistenceIssues.size,
        key = { state.profilePersistenceIssues[it].id },
    ) { index ->
        val issue = state.profilePersistenceIssues[index]
        SummaryCard(
            title = issue.title,
            detail = issue.detail,
            status = stringResource(R.string.status_needs_attention),
        )
    }
}

private fun LazyListScope.diagnosticActivity(state: LenswakeUiState) {
    item { SectionHeading(stringResource(R.string.section_activity)) }
    if (state.diagnosticEvents.isEmpty()) {
        item {
            ActionSection(
                title = stringResource(R.string.diagnostics_no_activity_title),
                detail = stringResource(R.string.diagnostics_no_activity_detail),
                actionLabel = stringResource(R.string.action_export_diagnostics),
                actionEnabled = state.actions.canExportDiagnostics,
                unavailableReason = state.actions.exportDiagnosticsUnavailableReason,
                onAction = {},
            )
        }
    } else {
        items(state.diagnosticEvents.size, key = { state.diagnosticEvents[it].id }) { index ->
            val event = state.diagnosticEvents[index]
            StatusRow(
                title = event.title,
                detail = stringResource(
                    R.string.diagnostics_timed_detail,
                    event.occurredAt,
                    event.detail,
                ),
                status = stringResource(R.string.status_recorded_event),
            )
            if (index < state.diagnosticEvents.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}
