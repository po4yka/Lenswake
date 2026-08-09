package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.component.CapabilityRow
import dev.po4yka.lenswake.ui.component.ReadinessCard
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SectionHeading

@Composable
fun SetupScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }
        item {
            ScreenHeader(
                title = "Setup",
                summary = "Each required capability must be observed on the target environment before scheduling is enabled.",
            )
        }
        item {
            ReadinessCard(
                readiness = state.readiness,
                onOpenSetup = {},
                showSetupAction = false,
            )
        }
        item { SectionHeading("Readiness checks") }
        items(state.capabilities.size, key = { state.capabilities[it].name }) { index ->
            CapabilityRow(capability = state.capabilities[index])
            if (index < state.capabilities.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}
