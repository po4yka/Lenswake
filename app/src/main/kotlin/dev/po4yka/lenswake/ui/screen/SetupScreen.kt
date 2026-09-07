package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.CapabilityStatus
import dev.po4yka.lenswake.ui.CapabilityUiState
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding
import dev.po4yka.lenswake.ui.component.CapabilityRow
import dev.po4yka.lenswake.ui.component.ReadinessCard
import dev.po4yka.lenswake.ui.component.SectionHeading

@Composable
fun SetupScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onRemediate: (SetupRemediationAction) -> Unit,
    onClearRemediationMessage: () -> Unit,
) {
    val (satisfied, outstanding) = state.capabilities.partition {
        it.status == CapabilityStatus.AVAILABLE
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scaffoldContentViewport(contentPadding),
        contentPadding = screenContentPadding(
            topMargin = 24.dp,
            bottomMargin = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.screen_setup_summary),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ReadinessCard(
                readiness = state.readiness,
                onOpenSetup = {},
                showSetupAction = false,
            )
        }
        item { SectionHeading(stringResource(R.string.section_readiness_checks)) }
        state.setupRemediationMessage?.let { message ->
            item {
                TextButton(onClick = onClearRemediationMessage) {
                    Text(message)
                }
            }
        }
        capabilityRows(outstanding, onRemediate)
        if (outstanding.isNotEmpty() && satisfied.isNotEmpty()) {
            item { SectionHeading(stringResource(R.string.section_readiness_satisfied)) }
        }
        capabilityRows(satisfied, onRemediate)
    }
}

private fun LazyListScope.capabilityRows(
    capabilities: List<CapabilityUiState>,
    onRemediate: (SetupRemediationAction) -> Unit,
) {
    items(capabilities.size, key = { capabilities[it].name }) { index ->
        CapabilityRow(
            capability = capabilities[index],
            onRemediate = onRemediate,
        )
        if (index < capabilities.lastIndex) {
            HorizontalDivider()
        }
    }
}
