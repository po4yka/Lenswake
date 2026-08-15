package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.SupportTier
import dev.po4yka.lenswake.ui.ProfileSummaryUiState
import dev.po4yka.lenswake.ui.ScheduleFormUiState

@Composable
internal fun ScheduleExperimentalConsent(
    form: ScheduleFormUiState,
    profiles: List<ProfileSummaryUiState>,
    enabled: Boolean,
    onUpdateForm: (ScheduleFormUiState) -> Unit,
) {
    val experimental = profiles.singleOrNull { it.id == form.profileId }
        ?.supportTier == SupportTier.EXPERIMENTAL
    if (!experimental) return
    val label = stringResource(R.string.schedule_experimental_consent_label)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.schedule_experimental_consent_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = label },
            checked = form.experimentalRiskAccepted,
            onCheckedChange = {
                onUpdateForm(form.copy(experimentalRiskAccepted = it))
            },
            enabled = enabled,
        )
    }
}
