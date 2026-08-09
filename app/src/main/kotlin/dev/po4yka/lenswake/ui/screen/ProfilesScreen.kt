package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.ProfileInstallUiState
import dev.po4yka.lenswake.ui.component.HonestEmptyState
import dev.po4yka.lenswake.ui.component.ReadinessCard
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SummaryCard

@Composable
fun ProfilesScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
    onOpenSetup: () -> Unit,
    onInstallCandidateProfile: () -> Unit,
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
                    title = "No profiles",
                    detail = "Install a candidate only when this device exactly matches a physically observed Pixel Camera environment.",
                    actionLabel = if (state.profileInstall is ProfileInstallUiState.Installing) {
                        "Installing candidate profile"
                    } else {
                        "Install candidate profile"
                    },
                    actionEnabled = state.actions.canInstallCandidateProfile,
                    unavailableReason = state.actions.installCandidateProfileUnavailableReason,
                    onAction = onInstallCandidateProfile,
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
        when (val install = state.profileInstall) {
            ProfileInstallUiState.Idle -> Unit
            ProfileInstallUiState.Installing -> item {
                SummaryCard(
                    title = "Installing candidate profile",
                    detail = "Lenswake is inspecting the current Pixel Camera environment and persisting an exact catalog match.",
                    status = "Installing",
                )
            }

            is ProfileInstallUiState.Succeeded -> item {
                SummaryCard(
                    title = "Candidate profile installed",
                    detail = install.message,
                    status = "Needs rehearsal",
                )
            }

            is ProfileInstallUiState.Failed -> item {
                SummaryCard(
                    title = "Candidate profile installation failed",
                    detail = install.message,
                    status = "Failed",
                )
            }
        }
        item {
            HonestEmptyState(
                title = "Production rehearsal not run",
                detail = "A production-path rehearsal is required before unattended automation can be trusted.",
                actionLabel = "Run rehearsal",
                actionEnabled = false,
                unavailableReason = state.actions.rehearsalUnavailableReason,
                onAction = {},
            )
        }
    }
}
