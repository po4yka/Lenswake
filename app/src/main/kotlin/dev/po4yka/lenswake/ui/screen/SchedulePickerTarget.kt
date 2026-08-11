package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.ScheduleFormUiState
import dev.po4yka.lenswake.ui.withStartDate
import dev.po4yka.lenswake.ui.withStartTime
import dev.po4yka.lenswake.ui.withStopDate
import dev.po4yka.lenswake.ui.withStopTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

internal enum class SchedulePickerTarget {
    START_DATE,
    START_TIME,
    STOP_DATE,
    STOP_TIME,
}

@Composable
internal fun ScheduleTimingSection(
    form: ScheduleFormUiState,
    timingError: String?,
    enabled: Boolean,
    onChoosePicker: (SchedulePickerTarget) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    ScheduleDateTimeField(
        title = stringResource(R.string.schedule_starts_label),
        value = form.startLocal,
        locale = locale,
        enabled = enabled,
        dateActionDescription = stringResource(R.string.schedule_choose_start_date),
        timeActionDescription = stringResource(R.string.schedule_choose_start_time),
        onChooseDate = { onChoosePicker(SchedulePickerTarget.START_DATE) },
        onChooseTime = { onChoosePicker(SchedulePickerTarget.START_TIME) },
    )
    ScheduleDateTimeField(
        title = stringResource(R.string.schedule_ends_label),
        value = form.stopLocal,
        locale = locale,
        enabled = enabled,
        dateActionDescription = stringResource(R.string.schedule_choose_end_date),
        timeActionDescription = stringResource(R.string.schedule_choose_end_time),
        onChooseDate = { onChoosePicker(SchedulePickerTarget.STOP_DATE) },
        onChooseTime = { onChoosePicker(SchedulePickerTarget.STOP_TIME) },
    )
    timingError?.let {
        Text(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(stringResource(R.string.schedule_time_zone_label), style = MaterialTheme.typography.titleSmall)
    Text(
        text = form.zoneId.getDisplayName(TextStyle.FULL, locale),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(R.string.schedule_time_zone_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    val dateLabel = value?.toLocalDate()?.toFormDateLabel(locale) ?: stringResource(R.string.schedule_choose_date)
    val timeLabel = value?.toLocalTime()?.toFormTimeLabel(locale) ?: stringResource(R.string.schedule_choose_time)
    val dateContentDescription = stringResource(
        R.string.schedule_picker_content_description,
        dateActionDescription,
        dateLabel,
    )
    val timeContentDescription = stringResource(
        R.string.schedule_picker_content_description,
        timeActionDescription,
        timeLabel,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp)
                    .semantics { contentDescription = dateContentDescription },
                enabled = enabled,
                onClick = onChooseDate,
            ) {
                Text(dateLabel)
            }
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minHeight = 48.dp)
                    .semantics { contentDescription = timeContentDescription },
                enabled = enabled,
                onClick = onChooseTime,
            ) {
                Text(timeLabel)
            }
        }
    }
}

@Composable
internal fun SchedulePickerDialog(
    target: SchedulePickerTarget?,
    form: ScheduleFormUiState,
    onDismiss: () -> Unit,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    when (target) {
        SchedulePickerTarget.START_DATE,
        SchedulePickerTarget.STOP_DATE,
        -> ScheduleDatePickerDialog(
            title = target.dateTitle(),
            initialDate = target.date(form),
            onDismiss = onDismiss,
            onConfirm = { onUpdateForm(target.withDate(form, it)); onDismiss() },
        )
        SchedulePickerTarget.START_TIME,
        SchedulePickerTarget.STOP_TIME,
        -> ScheduleTimePickerDialog(
            title = target.timeTitle(),
            initialTime = target.time(form),
            onDismiss = onDismiss,
            onConfirm = { onUpdateForm(target.withTime(form, it)); onDismiss() },
        )
        null -> Unit
    }
}

@Composable
private fun SchedulePickerTarget.dateTitle(): String = stringResource(
    if (this == SchedulePickerTarget.START_DATE) {
        R.string.schedule_start_date_title
    } else {
        R.string.schedule_end_date_title
    },
)

@Composable
private fun SchedulePickerTarget.timeTitle(): String = stringResource(
    if (this == SchedulePickerTarget.START_TIME) {
        R.string.schedule_start_time_title
    } else {
        R.string.schedule_end_time_title
    },
)

private fun SchedulePickerTarget.date(form: ScheduleFormUiState): LocalDate = when (this) {
    SchedulePickerTarget.START_DATE -> form.startLocal?.toLocalDate()
    SchedulePickerTarget.STOP_DATE -> form.stopLocal?.toLocalDate()
    else -> null
} ?: LocalDate.now(form.zoneId)

private fun SchedulePickerTarget.time(form: ScheduleFormUiState): LocalTime = when (this) {
    SchedulePickerTarget.START_TIME -> form.startLocal?.toLocalTime()
    SchedulePickerTarget.STOP_TIME -> form.stopLocal?.toLocalTime()
    else -> null
} ?: LocalTime.NOON

private fun SchedulePickerTarget.withDate(
    form: ScheduleFormUiState,
    date: LocalDate,
): ScheduleFormUiState = when (this) {
    SchedulePickerTarget.START_DATE -> form.withStartDate(date)
    SchedulePickerTarget.STOP_DATE -> form.withStopDate(date)
    else -> form
}

private fun SchedulePickerTarget.withTime(
    form: ScheduleFormUiState,
    time: LocalTime,
): ScheduleFormUiState = when (this) {
    SchedulePickerTarget.START_TIME -> form.withStartTime(time)
    SchedulePickerTarget.STOP_TIME -> form.withStopTime(time)
    else -> form
}

private fun LocalDate.toFormDateLabel(locale: Locale): String = format(
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
)

private fun LocalTime.toFormTimeLabel(locale: Locale): String = format(
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
)
