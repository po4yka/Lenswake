package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.RehearsalActionUiState
import dev.po4yka.lenswake.ui.ScheduleActionUiState
import dev.po4yka.lenswake.ui.ScheduleEditorUiState
import dev.po4yka.lenswake.ui.ScheduleFormUiState
import dev.po4yka.lenswake.ui.component.ActionSection
import dev.po4yka.lenswake.ui.component.ReadinessCard
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SummaryCard
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding

@Composable
fun SchedulesScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onOpenSetup: () -> Unit,
    onBeginCreate: () -> Unit,
    onBeginEdit: (String) -> Unit,
    onRunRehearsal: (String) -> Unit,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancelEditor: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRequestDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onClearOutcome: () -> Unit,
) {
    val busyMessage = (state.scheduleAction as? ScheduleActionUiState.Working)?.message
    SchedulesList(
        state = state,
        contentPadding = contentPadding,
        busyMessage = busyMessage,
        onOpenSetup = onOpenSetup,
        onBeginCreate = onBeginCreate,
        onBeginEdit = onBeginEdit,
        onRunRehearsal = onRunRehearsal,
        onUpdateForm = onUpdateForm,
        onSubmit = onSubmit,
        onCancelEditor = onCancelEditor,
        onSetEnabled = onSetEnabled,
        onRequestDelete = onRequestDelete,
        onClearOutcome = onClearOutcome,
    )
    PendingDeleteDialog(
        state = state,
        onCancelDelete = onCancelDelete,
        onConfirmDelete = onConfirmDelete,
    )
}

@Composable
private fun SchedulesList(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    busyMessage: String?,
    onOpenSetup: () -> Unit,
    onBeginCreate: () -> Unit,
    onBeginEdit: (String) -> Unit,
    onRunRehearsal: (String) -> Unit,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancelEditor: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRequestDelete: (String) -> Unit,
    onClearOutcome: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scaffoldContentViewport(contentPadding)
            .imePadding(),
        contentPadding = screenContentPadding(topMargin = 24.dp, bottomMargin = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        overviewItems(state, onOpenSetup, onClearOutcome)
        editorItem(state, busyMessage, onUpdateForm, onSubmit, onCancelEditor)
        scheduleItems(
            state = state,
            busy = busyMessage != null,
            onBeginCreate = onBeginCreate,
            onBeginEdit = onBeginEdit,
            onRunRehearsal = onRunRehearsal,
            onSetEnabled = onSetEnabled,
            onRequestDelete = onRequestDelete,
        )
    }
}

private fun LazyListScope.overviewItems(
    state: LenswakeUiState,
    onOpenSetup: () -> Unit,
    onClearOutcome: () -> Unit,
) {
    item {
        ScreenHeader(
            title = stringResource(R.string.nav_schedules),
            summary = stringResource(R.string.screen_schedules_summary),
        )
    }
    item { ReadinessCard(readiness = state.readiness, onOpenSetup = onOpenSetup) }
    state.activeSession?.let { activeSession ->
        item(key = "active-session-${activeSession.sessionId}") {
            SummaryCard(
                title = activeSession.title,
                detail = activeSession.detail,
                status = activeSession.status,
            )
        }
    }
    if (state.scheduleAction !is ScheduleActionUiState.Idle) {
        item { ScheduleOutcome(action = state.scheduleAction, onDismiss = onClearOutcome) }
    }
    if (state.rehearsal !is RehearsalActionUiState.Idle) {
        item { RehearsalOutcome(state.rehearsal) }
    }
}

private fun LazyListScope.editorItem(
    state: LenswakeUiState,
    busyMessage: String?,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancelEditor: () -> Unit,
) {
    val editor = state.scheduleEditor as? ScheduleEditorUiState.Open ?: return
    item {
        ScheduleEditor(
            editor = editor,
            profiles = state.profiles,
            busyMessage = busyMessage,
            onUpdateForm = onUpdateForm,
            onSubmit = onSubmit,
            onCancel = onCancelEditor,
        )
    }
}

private fun LazyListScope.scheduleItems(
    state: LenswakeUiState,
    busy: Boolean,
    onBeginCreate: () -> Unit,
    onBeginEdit: (String) -> Unit,
    onRunRehearsal: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    if (state.schedules.isEmpty()) {
        if (state.scheduleEditor is ScheduleEditorUiState.Closed) {
            item { EmptySchedules(state, onBeginCreate) }
        }
        return
    }
    if (state.scheduleEditor is ScheduleEditorUiState.Closed) {
        item { CreateScheduleAction(state, onBeginCreate) }
    }
    items(state.schedules, key = { it.id }) { schedule ->
        ScheduleCard(
            schedule = schedule,
            busy = busy,
            rehearsal = state.rehearsal,
            canRunRehearsal = state.actions.canRunRehearsal,
            rehearsalUnavailableReason = state.actions.rehearsalUnavailableReason,
            onEdit = { onBeginEdit(schedule.id) },
            onRunRehearsal = { onRunRehearsal(schedule.id) },
            onSetEnabled = { onSetEnabled(schedule.id, !schedule.enabled) },
            onRequestDelete = { onRequestDelete(schedule.id) },
        )
    }
}

@Composable
private fun EmptySchedules(
    state: LenswakeUiState,
    onBeginCreate: () -> Unit,
) {
    ActionSection(
        title = stringResource(R.string.schedules_empty_title),
        detail = stringResource(R.string.schedules_empty_detail),
        actionLabel = stringResource(R.string.action_create_schedule),
        actionEnabled = state.actions.canCreateSchedule,
        unavailableReason = state.actions.createScheduleUnavailableReason,
        onAction = onBeginCreate,
    )
}

@Composable
private fun CreateScheduleAction(
    state: LenswakeUiState,
    onBeginCreate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp),
            enabled = state.actions.canCreateSchedule,
            onClick = onBeginCreate,
        ) {
            Text(stringResource(R.string.action_create_schedule))
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

@Composable
private fun PendingDeleteDialog(
    state: LenswakeUiState,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
) {
    val schedule = state.pendingDeleteScheduleId?.let { scheduleId ->
        state.schedules.firstOrNull { it.id == scheduleId }
    } ?: return
    DeleteScheduleDialog(
        scheduleName = schedule.title,
        onDismiss = onCancelDelete,
        onConfirm = { onConfirmDelete(schedule.id) },
    )
}
