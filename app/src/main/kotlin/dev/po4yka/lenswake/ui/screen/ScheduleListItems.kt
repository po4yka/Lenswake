package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.RehearsalActionUiState
import dev.po4yka.lenswake.ui.RehearsalTargetUiState
import dev.po4yka.lenswake.ui.ScheduleSummaryUiState
import dev.po4yka.lenswake.ui.component.StatusIcon
import dev.po4yka.lenswake.ui.component.SummaryCard

@Composable
internal fun ScheduleCard(
    schedule: ScheduleSummaryUiState,
    busy: Boolean,
    rehearsal: RehearsalActionUiState,
    rehearsalTarget: RehearsalTargetUiState?,
    canRunRehearsal: Boolean,
    rehearsalUnavailableReason: String,
    onEdit: () -> Unit,
    onRunRehearsal: () -> Unit,
    onSetEnabled: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScheduleCardHeader(schedule)
            Text(schedule.timing, style = MaterialTheme.typography.bodyMedium)
            Text(schedule.capture.label(), style = MaterialTheme.typography.bodyMedium)
            ScheduleCardActions(
                schedule = schedule,
                busy = busy,
                rehearsal = rehearsal,
                rehearsalTarget = rehearsalTarget,
                canRunRehearsal = canRunRehearsal,
                rehearsalUnavailableReason = rehearsalUnavailableReason,
                onEdit = onEdit,
                onRunRehearsal = onRunRehearsal,
                onSetEnabled = onSetEnabled,
                onRequestDelete = onRequestDelete,
            )
        }
    }
}

@Composable
private fun ScheduleCardHeader(schedule: ScheduleSummaryUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(schedule.status)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                modifier = Modifier.semantics { heading() },
                text = schedule.title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(schedule.status, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ScheduleCardActions(
    schedule: ScheduleSummaryUiState,
    busy: Boolean,
    rehearsal: RehearsalActionUiState,
    rehearsalTarget: RehearsalTargetUiState?,
    canRunRehearsal: Boolean,
    rehearsalUnavailableReason: String,
    onEdit: () -> Unit,
    onRunRehearsal: () -> Unit,
    onSetEnabled: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val testInProgress = rehearsal is RehearsalActionUiState.Running &&
        rehearsalTarget == RehearsalTargetUiState.Schedule(schedule.id)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScheduleRehearsalAction(
            scheduleTitle = schedule.title,
            enabled = !busy && canRunRehearsal,
            testInProgress = testInProgress,
            onRunRehearsal = onRunRehearsal,
            modifier = Modifier.weight(1f),
        )
        ScheduleCardMenu(
            scheduleTitle = schedule.title,
            enabled = !busy,
            scheduleEnabled = schedule.enabled,
            onEdit = onEdit,
            onSetEnabled = onSetEnabled,
            onRequestDelete = onRequestDelete,
        )
    }
    if (showRehearsalUnavailableReason(busy, canRunRehearsal, testInProgress, rehearsalUnavailableReason)) {
        Text(
            text = rehearsalUnavailableReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleRehearsalAction(
    scheduleTitle: String,
    enabled: Boolean,
    testInProgress: Boolean,
    onRunRehearsal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val testNowDescription = stringResource(R.string.schedule_test_now_content_description, scheduleTitle)
    OutlinedButton(
        modifier = modifier.semantics { contentDescription = testNowDescription },
        enabled = enabled,
        onClick = onRunRehearsal,
    ) {
        Text(
            stringResource(
                if (testInProgress) R.string.profiles_testing else R.string.action_test_now,
            ),
        )
    }
}

@Composable
private fun ScheduleCardMenu(
    scheduleTitle: String,
    enabled: Boolean,
    scheduleEnabled: Boolean,
    onEdit: () -> Unit,
    onSetEnabled: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = enabled, onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert_24),
                contentDescription = stringResource(
                    R.string.schedule_more_actions_content_description,
                    scheduleTitle,
                ),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit)) },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (scheduleEnabled) R.string.action_disable else R.string.action_enable))
                },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onSetEnabled()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete_schedule)) },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onRequestDelete()
                },
            )
        }
    }
}

private fun showRehearsalUnavailableReason(
    busy: Boolean,
    canRunRehearsal: Boolean,
    testInProgress: Boolean,
    unavailableReason: String,
): Boolean {
    if (busy || canRunRehearsal) return false
    if (testInProgress) return false
    return unavailableReason.isNotBlank()
}

@Composable
internal fun RehearsalOutcome(
    rehearsal: RehearsalActionUiState,
    scheduleTitle: String?,
) {
    val targetTitle = scheduleTitle?.let {
        stringResource(R.string.schedule_rehearsal_title, it)
    }
    when (rehearsal) {
        RehearsalActionUiState.Idle -> Unit
        RehearsalActionUiState.Running -> SummaryCard(
            title = targetTitle ?: stringResource(R.string.profiles_test_title),
            detail = stringResource(R.string.profiles_test_running_detail),
            status = stringResource(R.string.status_working),
        )
        is RehearsalActionUiState.Passed -> SummaryCard(
            title = targetTitle ?: stringResource(R.string.profiles_test_passed_title),
            detail = rehearsal.message,
            status = stringResource(R.string.status_passed),
        )
        is RehearsalActionUiState.Failed -> SummaryCard(
            title = targetTitle ?: stringResource(R.string.profiles_test_failed_title),
            detail = rehearsal.message,
            status = stringResource(R.string.status_failed),
        )
        is RehearsalActionUiState.SafetyStopPending -> SummaryCard(
            title = targetTitle ?: stringResource(R.string.profiles_waiting_for_stop_title),
            detail = rehearsal.message,
            status = stringResource(R.string.status_safety_alarm_armed),
        )
    }
}
