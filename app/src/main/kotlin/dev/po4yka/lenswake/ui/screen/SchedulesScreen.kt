package dev.po4yka.lenswake.ui.screen

import android.text.format.DateFormat
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.po4yka.lenswake.ui.validateForDisplay
import dev.po4yka.lenswake.ui.withStartDate
import dev.po4yka.lenswake.ui.withStartTime
import dev.po4yka.lenswake.ui.withStopDate
import dev.po4yka.lenswake.ui.withStopTime
import dev.po4yka.lenswake.ui.component.ActionSection
import dev.po4yka.lenswake.ui.component.BusyButtonLabel
import dev.po4yka.lenswake.ui.component.ReadinessCard
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.StatusIcon
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

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
    val busyMessage = (state.scheduleAction as? ScheduleActionUiState.Working)?.message
    val busy = busyMessage != null
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
                    busyMessage = busyMessage,
                    onUpdateForm = onUpdateForm,
                    onSubmit = onSubmit,
                    onCancel = onCancelEditor,
                )
            }
        }
        if (state.schedules.isEmpty()) {
            if (editor is ScheduleEditorUiState.Closed) {
                item {
                    ActionSection(
                        title = "No schedules",
                        detail = "Add a schedule to choose when Pixel Camera should start and stop recording.",
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
                    onEdit = { onBeginEdit(schedule.id) },
                    onSetEnabled = { onSetEnabled(schedule.id, !schedule.enabled) },
                    onRequestDelete = { onRequestDelete(schedule.id) },
                )
            }
        }
    }

    val schedulePendingDelete = state.pendingDeleteScheduleId?.let { scheduleId ->
        state.schedules.firstOrNull { it.id == scheduleId }
    }
    schedulePendingDelete?.let { schedule ->
        DeleteScheduleDialog(
            scheduleName = schedule.title,
            onDismiss = onCancelDelete,
            onConfirm = { onConfirmDelete(schedule.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditor(
    editor: ScheduleEditorUiState.Open,
    profiles: List<ProfileSummaryUiState>,
    busyMessage: String?,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = busyMessage != null
    val form = editor.form
    val validation = form.validateForDisplay(profiles)
    val locale = LocalConfiguration.current.locales[0]
    var pickerTarget by rememberSaveable { mutableStateOf<SchedulePickerTarget?>(null) }
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
                text = "Records a 120× Time Lapse in Pixel Camera using the main rear lens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = form.name,
                onValueChange = { onUpdateForm(form.copy(name = it)) },
                enabled = !busy,
                label = { Text("Schedule name") },
                supportingText = {
                    Text(validation.nameError ?: "Shown in your schedule list.")
                },
                isError = validation.nameError != null,
                singleLine = true,
            )
            ScheduleDateTimeField(
                title = "Starts",
                value = form.startLocal,
                locale = locale,
                enabled = !busy,
                dateActionDescription = "Choose start date",
                timeActionDescription = "Choose start time",
                onChooseDate = { pickerTarget = SchedulePickerTarget.START_DATE },
                onChooseTime = { pickerTarget = SchedulePickerTarget.START_TIME },
            )
            ScheduleDateTimeField(
                title = "Ends",
                value = form.stopLocal,
                locale = locale,
                enabled = !busy,
                dateActionDescription = "Choose end date",
                timeActionDescription = "Choose end time",
                onChooseDate = { pickerTarget = SchedulePickerTarget.STOP_DATE },
                onChooseTime = { pickerTarget = SchedulePickerTarget.STOP_TIME },
            )
            validation.timingError?.let { timingError ->
                Text(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    text = timingError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text("Time zone", style = MaterialTheme.typography.titleSmall)
            Text(
                text = form.zoneId.getDisplayName(TextStyle.FULL, locale),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Dates and times stay tied to this time zone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier.semantics { heading() },
                text = "Camera setup",
                style = MaterialTheme.typography.titleMedium,
            )
            Column(modifier = Modifier.selectableGroup()) {
                profiles.forEachIndexed { index, profile ->
                    ProfileRadioOption(
                        profile = profile,
                        selected = form.profileId == profile.id,
                        enabled = !busy && profile.verifiedForScheduling,
                        onSelect = { onUpdateForm(form.copy(profileId = profile.id)) },
                    )
                    if (index < profiles.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
            validation.profileError?.let { profileError ->
                Text(
                    text = profileError,
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
                    Text("Activate schedule", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (form.enabled) {
                            "Start and stop alarms will be set when you save."
                        } else {
                            "Save as a draft without setting alarms."
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
                    .sizeIn(minHeight = 48.dp)
                    .semantics {
                        busyMessage?.let { message ->
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = message
                        }
                    },
                enabled = !busy && validation.canSubmit,
                onClick = onSubmit,
            ) {
                if (busyMessage != null) {
                    BusyButtonLabel(busyMessage)
                } else {
                    Text(if (editor.mode is ScheduleEditorMode.Create) "Save schedule" else "Save changes")
                }
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

    when (val target = pickerTarget) {
        SchedulePickerTarget.START_DATE,
        SchedulePickerTarget.STOP_DATE,
        -> ScheduleDatePickerDialog(
            title = if (target == SchedulePickerTarget.START_DATE) "Start date" else "End date",
            initialDate = if (target == SchedulePickerTarget.START_DATE) {
                form.startLocal?.toLocalDate()
            } else {
                form.stopLocal?.toLocalDate()
            } ?: LocalDate.now(form.zoneId),
            onDismiss = { pickerTarget = null },
            onConfirm = { date ->
                onUpdateForm(
                    if (target == SchedulePickerTarget.START_DATE) {
                        form.withStartDate(date)
                    } else {
                        form.withStopDate(date)
                    },
                )
                pickerTarget = null
            },
        )
        SchedulePickerTarget.START_TIME,
        SchedulePickerTarget.STOP_TIME,
        -> ScheduleTimePickerDialog(
            title = if (target == SchedulePickerTarget.START_TIME) "Start time" else "End time",
            initialTime = if (target == SchedulePickerTarget.START_TIME) {
                form.startLocal?.toLocalTime()
            } else {
                form.stopLocal?.toLocalTime()
            } ?: LocalTime.NOON,
            onDismiss = { pickerTarget = null },
            onConfirm = { time ->
                onUpdateForm(
                    if (target == SchedulePickerTarget.START_TIME) {
                        form.withStartTime(time)
                    } else {
                        form.withStopTime(time)
                    },
                )
                pickerTarget = null
            },
        )
        null -> Unit
    }
}

@Composable
private fun ProfileRadioOption(
    profile: ProfileSummaryUiState,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .sizeIn(minHeight = 56.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = profile.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = profile.environment,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (profile.verifiedForScheduling) {
                    "Verified for scheduling"
                } else {
                    "Test this camera setup before using it"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScheduleDateTimeField(
    title: String,
    value: LocalDateTime?,
    locale: Locale,
    enabled: Boolean,
    dateActionDescription: String,
    timeActionDescription: String,
    onChooseDate: () -> Unit,
    onChooseTime: () -> Unit,
) {
    val dateLabel = value?.toLocalDate()?.toFormDateLabel(locale) ?: "Choose date"
    val timeLabel = value?.toLocalTime()?.toFormTimeLabel(locale) ?: "Choose time"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp)
                    .semantics { contentDescription = "$dateActionDescription, $dateLabel" },
                enabled = enabled,
                onClick = onChooseDate,
            ) {
                Text(dateLabel)
            }
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp)
                    .semantics { contentDescription = "$timeActionDescription, $timeLabel" },
                enabled = enabled,
                onClick = onChooseTime,
            ) {
                Text(timeLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDatePickerDialog(
    title: String,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedDateMillis != null,
                onClick = {
                    pickerState.selectedDateMillis?.let { selectedMillis ->
                        onConfirm(Instant.ofEpochMilli(selectedMillis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) {
                Text("Use date")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    ) {
        DatePicker(
            state = pickerState,
            title = { Text(title, modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(
    title: String,
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    val pickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) },
            ) {
                Text("Use time")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private enum class SchedulePickerTarget {
    START_DATE,
    START_TIME,
    STOP_DATE,
    STOP_TIME,
}

private fun LocalDate.toFormDateLabel(locale: Locale): String = format(
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
)

private fun LocalTime.toFormTimeLabel(locale: Locale): String = format(
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
)

@Composable
private fun ScheduleCard(
    schedule: ScheduleSummaryUiState,
    busy: Boolean,
    onEdit: () -> Unit,
    onSetEnabled: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            Text(schedule.timing, style = MaterialTheme.typography.bodyMedium)
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
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = onRequestDelete,
            ) {
                Text("Delete schedule")
            }
        }
    }
}

@Composable
private fun DeleteScheduleDialog(
    scheduleName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $scheduleName?") },
        text = {
            Text("This schedule and its future recording times will be deleted. This can’t be undone.")
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                onClick = onConfirm,
            ) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ScheduleOutcome(
    action: ScheduleActionUiState,
    onDismiss: () -> Unit,
) {
    val statusLabel = when (action) {
        ScheduleActionUiState.Idle -> "Unknown"
        is ScheduleActionUiState.Working -> "Working"
        is ScheduleActionUiState.Succeeded -> "Completed"
        is ScheduleActionUiState.Failed -> "Failed"
    }
    val container = when (action) {
        ScheduleActionUiState.Idle -> MaterialTheme.colorScheme.surfaceContainerLow
        is ScheduleActionUiState.Working -> MaterialTheme.colorScheme.secondaryContainer
        is ScheduleActionUiState.Succeeded -> MaterialTheme.colorScheme.primaryContainer
        is ScheduleActionUiState.Failed -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (action) {
        ScheduleActionUiState.Idle -> MaterialTheme.colorScheme.onSurface
        is ScheduleActionUiState.Working -> MaterialTheme.colorScheme.onSecondaryContainer
        is ScheduleActionUiState.Succeeded -> MaterialTheme.colorScheme.onPrimaryContainer
        is ScheduleActionUiState.Failed -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusLabel
            },
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusIcon(statusLabel)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        text = "Some changes could not be undone:\n${action.rollbackFailures.joinToString("\n")}",
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
}
