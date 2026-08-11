package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.ui.ScheduleFormUiState

@Composable
internal fun CaptureConfigurationEditor(
    form: ScheduleFormUiState,
    supportedCaptures: Set<CaptureConfiguration>,
    enabled: Boolean,
    validationError: String?,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    CaptureChoiceGroup(
        title = stringResource(R.string.schedule_capture_mode_label),
        choices = CaptureMode.entries.map { mode ->
            CaptureChoice(mode, mode.label(), supportedCaptures.any { it.mode == mode })
        },
        selected = form.captureMode,
        enabled = enabled,
        onSelect = { mode ->
            supportedCaptures.preferredFor(mode, form)?.let { capture ->
                onUpdateForm(form.withCapture(capture))
            }
        },
    )
    if (form.captureMode == CaptureMode.TIME_LAPSE) {
        TimeLapseSpeedChoices(form, supportedCaptures, enabled, onUpdateForm)
    }
    LensChoices(form, supportedCaptures, enabled, onUpdateForm)
    validationError?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun TimeLapseSpeedChoices(
    form: ScheduleFormUiState,
    supportedCaptures: Set<CaptureConfiguration>,
    enabled: Boolean,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    val timeLapseCaptures = supportedCaptures.filterIsInstance<CaptureConfiguration.TimeLapse>()
    CaptureChoiceGroup(
        title = stringResource(R.string.schedule_time_lapse_speed_label),
        choices = TimeLapseSpeed.entries.map { speed ->
            CaptureChoice(speed, speed.label(), timeLapseCaptures.any { it.speed == speed })
        },
        selected = form.timeLapseSpeed,
        enabled = enabled,
        onSelect = { speed ->
            val capture = timeLapseCaptures.firstOrNull { it.speed == speed && it.lens == form.lens }
                ?: timeLapseCaptures.firstOrNull { it.speed == speed }
            capture?.let { onUpdateForm(form.withCapture(it)) }
        },
    )
}

@Composable
private fun LensChoices(
    form: ScheduleFormUiState,
    supportedCaptures: Set<CaptureConfiguration>,
    enabled: Boolean,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    CaptureChoiceGroup(
        title = stringResource(R.string.schedule_lens_label),
        choices = LensSelection.entries.map { lens ->
            CaptureChoice(lens, lens.label(), supportedCaptures.supports(form, lens))
        },
        selected = form.lens,
        enabled = enabled,
        onSelect = { lens ->
            supportedCaptures.firstOrNull { capture -> capture.matches(form, lens) }
                ?.let { onUpdateForm(form.withCapture(it)) }
        },
    )
}

@Composable
private fun <T> CaptureChoiceGroup(
    title: String,
    choices: List<CaptureChoice<T>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Column(modifier = Modifier.selectableGroup()) {
        choices.forEach { choice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == choice.value,
                        enabled = enabled && choice.enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(choice.value) },
                    )
                    .sizeIn(minHeight = 48.dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == choice.value,
                    onClick = null,
                    enabled = enabled && choice.enabled,
                )
                Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private data class CaptureChoice<T>(
    val value: T,
    val label: String,
    val enabled: Boolean,
)

internal fun Set<CaptureConfiguration>.preferredFor(
    mode: CaptureMode,
    form: ScheduleFormUiState,
): CaptureConfiguration? = sortedCaptureOptions()
    .firstOrNull { it.mode == mode && it.lens == form.lens && it.timeLapseSpeed == form.timeLapseSpeed }
    ?: sortedCaptureOptions().firstOrNull { it.mode == mode && it.lens == form.lens }
    ?: sortedCaptureOptions().firstOrNull { it.mode == mode }

internal fun Set<CaptureConfiguration>.preferredForAny(): CaptureConfiguration? =
    sortedCaptureOptions().firstOrNull()

private fun Set<CaptureConfiguration>.sortedCaptureOptions(): List<CaptureConfiguration> = sortedWith(
    compareBy<CaptureConfiguration>({ it.mode.ordinal }, { it.lens.ordinal }, { it.timeLapseSpeed?.ordinal ?: -1 }),
)

private fun Set<CaptureConfiguration>.supports(form: ScheduleFormUiState, lens: LensSelection): Boolean =
    any { it.matches(form, lens) }

private fun CaptureConfiguration.matches(form: ScheduleFormUiState, lens: LensSelection): Boolean =
    mode == form.captureMode &&
        this.lens == lens &&
        (this !is CaptureConfiguration.TimeLapse || speed == form.timeLapseSpeed)

internal fun ScheduleFormUiState.withCapture(capture: CaptureConfiguration): ScheduleFormUiState = copy(
    captureMode = capture.mode,
    timeLapseSpeed = capture.timeLapseSpeed ?: timeLapseSpeed,
    lens = capture.lens,
)
