package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.ProfileSummaryUiState
import dev.po4yka.lenswake.ui.ScheduleActionUiState
import dev.po4yka.lenswake.ui.ScheduleEditorMode
import dev.po4yka.lenswake.ui.ScheduleEditorUiState
import dev.po4yka.lenswake.ui.ScheduleFormUiState
import dev.po4yka.lenswake.ui.ScheduleSummaryUiState
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding
import dev.po4yka.lenswake.ui.component.HonestEmptyState
import dev.po4yka.lenswake.ui.component.ReadinessCard
import dev.po4yka.lenswake.ui.component.ScreenHeader

@Composable
fun SchedulesScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onOpenSetup: () -> Unit,
    onBeginCreate: () -> Unit,
    onBeginEdit: (String) -> Unit,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancelEditor: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRequestDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onClearOutcome: () -> Unit,
) {
    val busy = state.scheduleAction is ScheduleActionUiState.Working
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scaffoldContentViewport(contentPadding)
            .imePadding(),
        contentPadding = screenContentPadding(
            topMargin = 24.dp,
            bottomMargin = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ScreenHeader(
                title = "Schedules",
                summary = "Plan unattended Time Lapse sessions in the native Pixel Camera.",
            )
        }
        item {
            ReadinessCard(
                readiness = state.readiness,
                onOpenSetup = onOpenSetup,
            )
        }
        if (state.scheduleAction !is ScheduleActionUiState.Idle) {
            item {
                ScheduleOutcome(
                    action = state.scheduleAction,
                    onDismiss = onClearOutcome,
                )
            }
        }
        val editor = state.scheduleEditor
        if (editor is ScheduleEditorUiState.Open) {
            item {
                ScheduleEditor(
                    editor = editor,
                    profiles = state.profiles,
                    busy = busy,
                    onUpdateForm = onUpdateForm,
                    onSubmit = onSubmit,
                    onCancel = onCancelEditor,
                )
            }
        }
        if (state.schedules.isEmpty()) {
            if (editor is ScheduleEditorUiState.Closed) {
                item {
                    HonestEmptyState(
                        title = "No schedules",
                        detail = "Nothing is scheduled. Lenswake will not launch Pixel Camera until a persisted schedule is created.",
                        actionLabel = "Create schedule",
                        actionEnabled = state.actions.canCreateSchedule,
                        unavailableReason = state.actions.createScheduleUnavailableReason,
                        onAction = onBeginCreate,
                    )
                }
            }
        } else {
            if (editor is ScheduleEditorUiState.Closed) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 48.dp),
                            enabled = state.actions.canCreateSchedule,
                            onClick = onBeginCreate,
                        ) {
                            Text("Create schedule")
                        }
                        if (!state.actions.canCreateSchedule) {
                            Text(
                                text = state.actions.createScheduleUnavailableReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(state.schedules.size, key = { state.schedules[it].id }) { index ->
                val schedule = state.schedules[index]
                ScheduleCard(
                    schedule = schedule,
                    busy = busy,
                    confirmingDelete = state.pendingDeleteScheduleId == schedule.id,
                    onEdit = { onBeginEdit(schedule.id) },
                    onSetEnabled = { onSetEnabled(schedule.id, !schedule.enabled) },
                    onRequestDelete = { onRequestDelete(schedule.id) },
                    onCancelDelete = onCancelDelete,
                    onConfirmDelete = { onConfirmDelete(schedule.id) },
                )
            }
        }
    }
}

@Composable
private fun ScheduleEditor(
    editor: ScheduleEditorUiState.Open,
    profiles: List<ProfileSummaryUiState>,
    busy: Boolean,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = editor.form
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = when (editor.mode) {
                    ScheduleEditorMode.Create -> "Create schedule"
                    is ScheduleEditorMode.Edit -> "Edit schedule"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Capture is fixed to native Pixel Camera Time Lapse · 120× · rear main lens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = form.name,
                onValueChange = { onUpdateForm(form.copy(name = it)) },
                enabled = !busy,
                label = { Text("Name") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = form.startLocal,
                onValueChange = { onUpdateForm(form.copy(startLocal = it)) },
                enabled = !busy,
                label = { Text("Start local time") },
                supportingText = { Text("Format: YYYY-MM-DDTHH:MM") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = form.stopLocal,
                onValueChange = { onUpdateForm(form.copy(stopLocal = it)) },
                enabled = !busy,
                label = { Text("Stop local time") },
                supportingText = { Text("Format: YYYY-MM-DDTHH:MM") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = form.zoneId,
                onValueChange = { onUpdateForm(form.copy(zoneId = it)) },
                enabled = !busy,
                label = { Text("IANA time zone") },
                supportingText = { Text("Example: Asia/Tbilisi") },
                singleLine = true,
            )
            Text(
                modifier = Modifier.semantics { heading() },
                text = "Installed Pixel Camera profile",
                style = MaterialTheme.typography.titleMedium,
            )
            profiles.forEach { profile ->
                FilterChip(
                    modifier = Modifier.fillMaxWidth(),
                    selected = form.profileId == profile.id,
                    onClick = { onUpdateForm(form.copy(profileId = profile.id)) },
                    enabled = !busy && profile.verifiedForScheduling,
                    label = {
                        Column {
                            Text(profile.title)
                            Text(
                                text = profile.environment,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!profile.verifiedForScheduling) {
                                Text(
                                    text = "Not available for scheduling: ${profile.compatibility}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                )
            }
            if (profiles.none { it.id == form.profileId && it.verifiedForScheduling }) {
                Text(
                    text = "Choose an installed profile that has passed a production rehearsal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enabled", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (form.enabled) {
                            "Saving requires both exact alarms to register."
                        } else {
                            "The schedule is persisted without alarms."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.enabled,
                    onCheckedChange = { onUpdateForm(form.copy(enabled = it)) },
                    enabled = !busy,
                )
            }
            editor.error?.let { error ->
                Text(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp),
                enabled = !busy && profiles.any { it.id == form.profileId && it.verifiedForScheduling },
                onClick = onSubmit,
            ) {
                Text(if (editor.mode is ScheduleEditorMode.Create) "Create and apply" else "Save and apply")
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp),
                enabled = !busy,
                onClick = onCancel,
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    schedule: ScheduleSummaryUiState,
    busy: Boolean,
    confirmingDelete: Boolean,
    onEdit: () -> Unit,
    onSetEnabled: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = schedule.title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(schedule.status, style = MaterialTheme.typography.labelLarge)
            Text(schedule.timing, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Profile: ${schedule.profileId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = onEdit,
            ) {
                Text("Edit")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = onSetEnabled,
            ) {
                Text(if (schedule.enabled) "Disable" else "Enable")
            }
            if (confirmingDelete) {
                Text(
                    text = "Delete this schedule? Its START and STOP alarms will be cancelled first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = onConfirmDelete,
                ) {
                    Text("Confirm delete")
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = onCancelDelete,
                ) {
                    Text("Keep schedule")
                }
            } else {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = onRequestDelete,
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ScheduleOutcome(
    action: ScheduleActionUiState,
    onDismiss: () -> Unit,
) {
    val failed = action is ScheduleActionUiState.Failed
    val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val message = when (action) {
                ScheduleActionUiState.Idle -> ""
                is ScheduleActionUiState.Working -> action.message
                is ScheduleActionUiState.Succeeded -> action.message
                is ScheduleActionUiState.Failed -> action.message
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (action is ScheduleActionUiState.Failed && action.rollbackFailures.isNotEmpty()) {
                Text(
                    text = "Rollback needs attention:\n${action.rollbackFailures.joinToString("\n")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (action !is ScheduleActionUiState.Working) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}
