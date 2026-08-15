package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.AndroidUiStringProvider
import dev.po4yka.lenswake.ui.ProfileSummaryUiState
import dev.po4yka.lenswake.ui.ScheduleEditorMode
import dev.po4yka.lenswake.ui.ScheduleEditorUiState
import dev.po4yka.lenswake.ui.ScheduleFormUiState
import dev.po4yka.lenswake.ui.ScheduleFormValidation
import dev.po4yka.lenswake.ui.captureConfiguration
import dev.po4yka.lenswake.ui.component.BusyButtonLabel
import dev.po4yka.lenswake.ui.validateForDisplay

@Composable
internal fun ScheduleEditor(
    editor: ScheduleEditorUiState.Open,
    profiles: List<ProfileSummaryUiState>,
    busyMessage: String?,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = editor.form
    val context = LocalContext.current
    val strings = remember(context) { AndroidUiStringProvider(context) }
    val validation = form.validateForDisplay(profiles, strings = strings)
    var pickerTarget by rememberSaveable { mutableStateOf<SchedulePickerTarget?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        ScheduleEditorContent(
            editor = editor,
            profiles = profiles,
            validation = validation,
            busyMessage = busyMessage,
            onUpdateForm = onUpdateForm,
            onSubmit = onSubmit,
            onCancel = onCancel,
            onChoosePicker = { pickerTarget = it },
        )
    }
    SchedulePickerDialog(
        target = pickerTarget,
        form = form,
        onDismiss = { pickerTarget = null },
        onUpdateForm = onUpdateForm,
    )
}

@Composable
private fun ScheduleEditorContent(
    editor: ScheduleEditorUiState.Open,
    profiles: List<ProfileSummaryUiState>,
    validation: ScheduleFormValidation,
    busyMessage: String?,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onChoosePicker: (SchedulePickerTarget) -> Unit,
) {
    val form = editor.form
    val enabled = busyMessage == null
    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScheduleEditorHeader(editor.mode)
        ScheduleNameField(form, validation.nameError, enabled, onUpdateForm)
        ScheduleTimingSection(form, validation.timingError, enabled, onChoosePicker)
        ScheduleCameraSection(form, profiles, validation.captureError, enabled, onUpdateForm)
        ScheduleProfileSection(form, profiles, validation.profileError, enabled, onUpdateForm)
        ScheduleExperimentalConsent(form, profiles, enabled, onUpdateForm)
        ScheduleActivationSection(form, enabled, onUpdateForm)
        ScheduleEditorError(editor.error)
        ScheduleEditorActions(editor.mode, validation.canSubmit, busyMessage, onSubmit, onCancel)
    }
}

@Composable
private fun ScheduleEditorHeader(mode: ScheduleEditorMode) {
    Text(
        modifier = Modifier.semantics { heading() },
        text = when (mode) {
            ScheduleEditorMode.Create -> stringResource(R.string.schedule_editor_create_title)
            is ScheduleEditorMode.Edit -> stringResource(R.string.schedule_editor_edit_title)
        },
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = stringResource(R.string.schedule_editor_summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScheduleNameField(
    form: ScheduleFormUiState,
    nameError: String?,
    enabled: Boolean,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = form.name,
        onValueChange = { onUpdateForm(form.copy(name = it)) },
        enabled = enabled,
        label = { Text(stringResource(R.string.schedule_name_label)) },
        supportingText = { Text(nameError ?: stringResource(R.string.schedule_name_supporting_text)) },
        isError = nameError != null,
        singleLine = true,
    )
}

@Composable
private fun ScheduleCameraSection(
    form: ScheduleFormUiState,
    profiles: List<ProfileSummaryUiState>,
    captureError: String?,
    enabled: Boolean,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    val supportedCaptures = profiles.singleOrNull { it.id == form.profileId }?.supportedCaptures.orEmpty()
    Text(
        modifier = Modifier.semantics { heading() },
        text = stringResource(R.string.schedule_camera_setup_label),
        style = MaterialTheme.typography.titleMedium,
    )
    CaptureConfigurationEditor(
        form = form,
        supportedCaptures = supportedCaptures,
        enabled = enabled,
        validationError = captureError,
        onUpdateForm = onUpdateForm,
    )
}

@Composable
private fun ScheduleProfileSection(
    form: ScheduleFormUiState,
    profiles: List<ProfileSummaryUiState>,
    profileError: String?,
    enabled: Boolean,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        profiles.forEachIndexed { index, profile ->
            ProfileRadioOption(
                profile = profile,
                selected = form.profileId == profile.id,
                enabled = enabled && profile.verifiedForScheduling,
                onSelect = { onUpdateForm(form.forProfile(profile)) },
            )
            if (index < profiles.lastIndex) HorizontalDivider()
        }
    }
    profileError?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
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
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .sizeIn(minHeight = 56.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                text = "${profile.supportTier.label()} · ${profile.environment}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (profile.verifiedForScheduling) {
                        R.string.schedule_profile_verified
                    } else {
                        R.string.schedule_profile_needs_test
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScheduleActivationSection(
    form: ScheduleFormUiState,
    enabled: Boolean,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    val activationLabel = stringResource(R.string.schedule_activate_label)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(activationLabel, style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    if (form.enabled) R.string.schedule_activate_detail else R.string.schedule_draft_detail,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = activationLabel },
            checked = form.enabled,
            onCheckedChange = { onUpdateForm(form.copy(enabled = it)) },
            enabled = enabled,
        )
    }
}

@Composable
private fun ScheduleEditorError(error: String?) {
    error?.let {
        Text(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ScheduleEditorActions(
    mode: ScheduleEditorMode,
    canSubmit: Boolean,
    busyMessage: String?,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .semantics {
                busyMessage?.let {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = it
                }
            },
        enabled = busyMessage == null && canSubmit,
        onClick = onSubmit,
    ) {
        if (busyMessage != null) {
            BusyButtonLabel(busyMessage)
        } else {
            Text(
                stringResource(
                    if (mode is ScheduleEditorMode.Create) {
                        R.string.action_save_schedule
                    } else {
                        R.string.action_save_changes
                    },
                ),
            )
        }
    }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        enabled = busyMessage == null,
        onClick = onCancel,
    ) {
        Text(stringResource(R.string.action_cancel))
    }
}

private fun ScheduleFormUiState.forProfile(profile: ProfileSummaryUiState): ScheduleFormUiState {
    val selectedCapture = captureConfiguration()
        .takeIf { it in profile.supportedCaptures }
        ?: profile.supportedCaptures.preferredFor(captureMode, this)
        ?: profile.supportedCaptures.preferredForAny()
    return if (selectedCapture == null) {
        copy(
            profileId = profile.id,
            experimentalRiskAccepted = false,
        )
    } else {
        copy(
            profileId = profile.id,
            experimentalRiskAccepted = false,
        ).withCapture(selectedCapture)
    }
}
