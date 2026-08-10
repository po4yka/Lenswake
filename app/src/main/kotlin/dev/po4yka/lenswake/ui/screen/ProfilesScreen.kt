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
import dev.po4yka.lenswake.ui.RehearsalActionUiState
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding
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
    onRunRehearsal: () -> Unit,
) {
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
        item {
            HonestEmptyState(
                title = if (state.profiles.isEmpty()) "No profiles" else "Catalog profile",
                detail = if (state.profiles.isEmpty()) {
                    "Install a candidate only when this device exactly matches a physically observed Pixel Camera environment."
                } else {
                    "Check the installed profile against the exact-device catalog. A changed selector definition replaces the stale candidate and requires rehearsal again."
                },
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
        if (state.profiles.isNotEmpty()) {
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
                title = when (state.rehearsal) {
                    RehearsalActionUiState.Idle -> "Production rehearsal not run"
                    RehearsalActionUiState.Running -> "Production rehearsal running"
                    is RehearsalActionUiState.Passed -> "Production rehearsal passed"
                    is RehearsalActionUiState.Failed -> "Production rehearsal failed"
                    is RehearsalActionUiState.SafetyStopPending -> "Safety STOP pending"
                },
                detail = "This screen-on production-path check records for 10 seconds at 120x using the rear main lens. " +
                    "Passing it verifies the exercised start/stop profile path, but unattended scheduling remains blocked until device wake is available.",
                actionLabel = if (state.rehearsal is RehearsalActionUiState.Running) {
                    "Running rehearsal"
                } else {
                    "Run rehearsal"
                },
                actionEnabled = state.actions.canRunRehearsal,
                unavailableReason = state.actions.rehearsalUnavailableReason,
                onAction = onRunRehearsal,
            )
        }
        when (val rehearsal = state.rehearsal) {
            RehearsalActionUiState.Idle -> Unit
            RehearsalActionUiState.Running -> item {
                SummaryCard(
                    title = "Rehearsal in progress",
                    detail = "Lenswake armed the independent session-bound STOP alarm before starting Pixel Camera automation.",
                    status = "Running",
                )
            }
            is RehearsalActionUiState.Passed -> item {
                SummaryCard(
                    title = "Rehearsal verified",
                    detail = rehearsal.message,
                    status = "Passed",
                )
            }
            is RehearsalActionUiState.Failed -> item {
                SummaryCard(
                    title = "Rehearsal failed",
                    detail = rehearsal.message,
                    status = "Failed",
                )
            }
            is RehearsalActionUiState.SafetyStopPending -> item {
                SummaryCard(
                    title = "STOP verification pending",
                    detail = rehearsal.message,
                    status = "Safety alarm armed",
                )
            }
        }
    }
}
