package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.component.HonestEmptyState
import dev.po4yka.lenswake.ui.component.ReadinessCard
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SummaryCard

@Composable
fun ProfilesScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onOpenSetup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = contentPadding.calculateTopPadding() + 24.dp,
            end = 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            ScreenHeader(
                title = "Profiles",
                summary = "Profiles bind empirically verified selectors to one Pixel Camera environment.",
            )
        }
        item {
            ReadinessCard(
                readiness = state.readiness,
                onOpenSetup = onOpenSetup,
            )
        }
        if (state.profiles.isEmpty()) {
            item {
                HonestEmptyState(
                    title = "No verified profiles",
                    detail = "No selector profile has been calibrated for this device, Android build, Pixel Camera version, locale, and display.",
                    actionLabel = "Calibrate profile",
                    actionEnabled = state.actions.canCalibrateProfile,
                    unavailableReason = state.actions.calibrateProfileUnavailableReason,
                    onAction = {},
                )
            }
            item {
                HonestEmptyState(
                    title = "Rehearsal not run",
                    detail = "A production-path rehearsal is required before unattended automation can be trusted.",
                    actionLabel = "Run rehearsal",
                    actionEnabled = state.actions.canRunRehearsal,
                    unavailableReason = state.actions.rehearsalUnavailableReason,
                    onAction = {},
                )
            }
        } else {
            items(state.profiles.size, key = { state.profiles[it].id }) { index ->
                val profile = state.profiles[index]
                SummaryCard(
                    title = profile.title,
                    detail = profile.environment,
                    status = profile.compatibility,
                )
            }
        }
    }
}
