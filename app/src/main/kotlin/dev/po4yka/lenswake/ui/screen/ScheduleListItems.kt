package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.RehearsalActionUiState
import dev.po4yka.lenswake.ui.ScheduleSummaryUiState
import dev.po4yka.lenswake.ui.component.StatusIcon
import dev.po4yka.lenswake.ui.component.SummaryCard

@Composable
internal fun ScheduleCard(
    schedule: ScheduleSummaryUiState,
    busy: Boolean,
    rehearsal: RehearsalActionUiState,
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
            ScheduleRehearsalAction(
                scheduleTitle = schedule.title,
                busy = busy,
                rehearsal = rehearsal,
                canRunRehearsal = canRunRehearsal,
                unavailableReason = rehearsalUnavailableReason,
                onRunRehearsal = onRunRehearsal,
            )
            ScheduleCardActions(
                enabled = !busy,
                scheduleEnabled = schedule.enabled,
                onEdit = onEdit,
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
private fun ScheduleRehearsalAction(
    scheduleTitle: String,
    busy: Boolean,
    rehearsal: RehearsalActionUiState,
    canRunRehearsal: Boolean,
    unavailableReason: String,
    onRunRehearsal: () -> Unit,
) {
    val testNowDescription = stringResource(R.string.schedule_test_now_content_description, scheduleTitle)
    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = testNowDescription },
        enabled = !busy && canRunRehearsal,
        onClick = onRunRehearsal,
    ) {
        Text(
            stringResource(
                if (rehearsal is RehearsalActionUiState.Running) {
                    R.string.profiles_testing
                } else {
                    R.string.action_test_now
                },
            ),
        )
    }
    if (showRehearsalUnavailableReason(busy, canRunRehearsal, rehearsal, unavailableReason)) {
        Text(
            text = unavailableReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleCardActions(
    enabled: Boolean,
    scheduleEnabled: Boolean,
    onEdit: () -> Unit,
    onSetEnabled: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onEdit) {
        Text(stringResource(R.string.action_edit))
    }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onSetEnabled) {
        Text(stringResource(if (scheduleEnabled) R.string.action_disable else R.string.action_enable))
    }
    TextButton(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onRequestDelete) {
        Text(stringResource(R.string.action_delete_schedule))
    }
}

private fun showRehearsalUnavailableReason(
    busy: Boolean,
    canRunRehearsal: Boolean,
    rehearsal: RehearsalActionUiState,
    unavailableReason: String,
): Boolean {
    if (busy || canRunRehearsal) return false
    if (rehearsal is RehearsalActionUiState.Running) return false
    return unavailableReason.isNotBlank()
}

@Composable
internal fun RehearsalOutcome(rehearsal: RehearsalActionUiState) {
    when (rehearsal) {
        RehearsalActionUiState.Idle -> Unit
        RehearsalActionUiState.Running -> SummaryCard(
            title = stringResource(R.string.profiles_test_title),
            detail = stringResource(R.string.profiles_test_running_detail),
            status = stringResource(R.string.status_working),
        )
        is RehearsalActionUiState.Passed -> SummaryCard(
            title = stringResource(R.string.profiles_test_passed_title),
            detail = rehearsal.message,
            status = stringResource(R.string.status_passed),
        )
        is RehearsalActionUiState.Failed -> SummaryCard(
            title = stringResource(R.string.profiles_test_failed_title),
            detail = rehearsal.message,
            status = stringResource(R.string.status_failed),
        )
        is RehearsalActionUiState.SafetyStopPending -> SummaryCard(
            title = stringResource(R.string.profiles_waiting_for_stop_title),
            detail = rehearsal.message,
            status = stringResource(R.string.status_safety_alarm_armed),
        )
    }
}
