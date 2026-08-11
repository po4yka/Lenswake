package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.ScheduleActionUiState
import dev.po4yka.lenswake.ui.component.StatusIcon

@Composable
internal fun DeleteScheduleDialog(
    scheduleName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_delete_title, scheduleName)) },
        text = { Text(stringResource(R.string.schedule_delete_message)) },
        confirmButton = {
            TextButton(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                onClick = onConfirm,
            ) {
                Text(text = stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun ScheduleOutcome(
    action: ScheduleActionUiState,
    onDismiss: () -> Unit,
) {
    val statusLabel = action.statusLabel()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusLabel
            },
        colors = CardDefaults.cardColors(
            containerColor = action.containerColor(),
            contentColor = action.contentColor(),
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusIcon(statusLabel)
            ScheduleOutcomeContent(action, onDismiss)
        }
    }
}

@Composable
private fun ScheduleOutcomeContent(
    action: ScheduleActionUiState,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = action.message(), style = MaterialTheme.typography.bodyLarge)
        if (action is ScheduleActionUiState.Failed && action.rollbackFailures.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.schedule_rollback_failures,
                    action.rollbackFailures.joinToString("\n"),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (action !is ScheduleActionUiState.Working) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

@Composable
private fun ScheduleActionUiState.statusLabel(): String = when (this) {
    ScheduleActionUiState.Idle -> stringResource(R.string.status_unknown)
    is ScheduleActionUiState.Working -> stringResource(R.string.status_working)
    is ScheduleActionUiState.Succeeded -> stringResource(R.string.status_completed)
    is ScheduleActionUiState.Failed -> stringResource(R.string.status_failed)
}

@Composable
private fun ScheduleActionUiState.containerColor(): Color = when (this) {
    ScheduleActionUiState.Idle -> MaterialTheme.colorScheme.surfaceContainerLow
    is ScheduleActionUiState.Working -> MaterialTheme.colorScheme.secondaryContainer
    is ScheduleActionUiState.Succeeded -> MaterialTheme.colorScheme.primaryContainer
    is ScheduleActionUiState.Failed -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun ScheduleActionUiState.contentColor(): Color = when (this) {
    ScheduleActionUiState.Idle -> MaterialTheme.colorScheme.onSurface
    is ScheduleActionUiState.Working -> MaterialTheme.colorScheme.onSecondaryContainer
    is ScheduleActionUiState.Succeeded -> MaterialTheme.colorScheme.onPrimaryContainer
    is ScheduleActionUiState.Failed -> MaterialTheme.colorScheme.onErrorContainer
}

private fun ScheduleActionUiState.message(): String = when (this) {
    ScheduleActionUiState.Idle -> ""
    is ScheduleActionUiState.Working -> message
    is ScheduleActionUiState.Succeeded -> message
    is ScheduleActionUiState.Failed -> message
}
