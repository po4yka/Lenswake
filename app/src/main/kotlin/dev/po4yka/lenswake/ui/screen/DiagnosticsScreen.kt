package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding
import dev.po4yka.lenswake.ui.component.CapabilityRow
import dev.po4yka.lenswake.ui.component.ActionSection
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SectionHeading
import dev.po4yka.lenswake.ui.component.SummaryCard
import dev.po4yka.lenswake.ui.component.StatusRow

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
        item {
            ScreenHeader(
                title = "Diagnostics",
                summary = "Review device setup, recent issues, and recording activity.",
            )
        }
        item { SectionHeading("Capabilities") }
        items(state.capabilities.size, key = { state.capabilities[it].name }) { index ->
            CapabilityRow(capability = state.capabilities[index])
            if (index < state.capabilities.lastIndex) {
                HorizontalDivider()
            }
        }
        if (state.alarmTransportIncidents.isNotEmpty()) {
            item { SectionHeading("Scheduled alarm failures") }
            items(state.alarmTransportIncidents.size, key = { state.alarmTransportIncidents[it].id }) { index ->
                val incident = state.alarmTransportIncidents[index]
                SummaryCard(
                    title = incident.title,
                    detail = "${incident.occurredAt} · ${incident.detail}",
                    status = "Needs attention",
                    actionLabel = if (incident.action == dev.po4yka.lenswake.ui.AlarmTransportIncidentUiAction.OPEN_PIXEL_CAMERA) {
                        "Open Pixel Camera"
                    } else {
                        null
                    },
                    onAction = if (incident.action == dev.po4yka.lenswake.ui.AlarmTransportIncidentUiAction.OPEN_PIXEL_CAMERA) {
                        onOpenPixelCamera
                    } else {
                        null
                    },
                )
            }
        }
        item { SectionHeading("Activity") }
        if (state.diagnosticEvents.isEmpty()) {
            item {
                ActionSection(
                    title = "No activity yet",
                    detail = "Recording activity and errors will appear here after Lenswake runs.",
                    actionLabel = "Export diagnostics",
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
                    detail = "${event.occurredAt} · ${event.detail}",
                    status = "Recorded event",
                )
                if (index < state.diagnosticEvents.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}
