package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.ProfileInstallUiState
import dev.po4yka.lenswake.ui.RehearsalActionUiState
import dev.po4yka.lenswake.ui.scaffoldContentViewport
import dev.po4yka.lenswake.ui.screenContentPadding
import dev.po4yka.lenswake.ui.component.ActionSection
import dev.po4yka.lenswake.ui.component.ScreenHeader
import dev.po4yka.lenswake.ui.component.SummaryCard
import dev.po4yka.lenswake.ui.component.StatusRow

@Composable
fun ProfilesScreen(
    state: LenswakeUiState,
    contentPadding: PaddingValues,
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
                summary = "Set up and test the Pixel Camera controls used for scheduled recordings.",
            )
        }
        item {
            ActionSection(
                title = "Camera profile",
                detail = if (state.profiles.isEmpty()) {
                    "Install the profile that matches this Pixel and Pixel Camera version."
                } else {
                    "Check for an updated profile after Pixel Camera changes."
                },
                actionLabel = if (state.profileInstall is ProfileInstallUiState.Installing) {
                    "Installing profile"
                } else {
                    "Install camera profile"
                },
                actionEnabled = state.actions.canInstallCandidateProfile,
                actionInProgress = state.profileInstall is ProfileInstallUiState.Installing,
                unavailableReason = state.actions.installCandidateProfileUnavailableReason,
                onAction = onInstallCandidateProfile,
            )
        }
        if (state.profiles.isNotEmpty()) {
            items(state.profiles.size, key = { state.profiles[it].id }) { index ->
                val profile = state.profiles[index]
                StatusRow(
                    title = profile.title,
                    detail = profile.environment,
                    status = profile.compatibility,
                )
                if (index < state.profiles.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
        when (val install = state.profileInstall) {
            ProfileInstallUiState.Idle -> Unit
            ProfileInstallUiState.Installing -> Unit

            is ProfileInstallUiState.Succeeded -> item {
                SummaryCard(
                    title = "Camera profile installed",
                    detail = install.message,
                    status = "Needs test",
                )
            }

            is ProfileInstallUiState.Failed -> item {
                SummaryCard(
                    title = "Camera profile could not be installed",
                    detail = install.message,
                    status = "Failed",
                )
            }
        }
        item {
            ActionSection(
                title = "Test recording",
                detail = if (state.rehearsal is RehearsalActionUiState.Running) {
                    "Pixel Camera is recording briefly. Lenswake will stop it automatically."
                } else {
                    "Record a 10-second 120× Time Lapse now to confirm that Lenswake can start and stop Pixel Camera on this device."
                },
                actionLabel = if (state.rehearsal is RehearsalActionUiState.Running) {
                    "Testing camera"
                } else {
                    "Test recording"
                },
                actionEnabled = state.actions.canRunRehearsal,
                actionInProgress = state.rehearsal is RehearsalActionUiState.Running,
                unavailableReason = state.actions.rehearsalUnavailableReason,
                onAction = onRunRehearsal,
            )
        }
        when (val rehearsal = state.rehearsal) {
            RehearsalActionUiState.Idle -> Unit
            RehearsalActionUiState.Running -> Unit
            is RehearsalActionUiState.Passed -> item {
                SummaryCard(
                    title = "Test recording passed",
                    detail = rehearsal.message,
                    status = "Passed",
                )
            }
            is RehearsalActionUiState.Failed -> item {
                SummaryCard(
                    title = "Test recording failed",
                    detail = rehearsal.message,
                    status = "Failed",
                )
            }
            is RehearsalActionUiState.SafetyStopPending -> item {
                SummaryCard(
                    title = "Waiting for Pixel Camera to stop",
                    detail = rehearsal.message,
                    status = "Safety alarm armed",
                )
            }
        }
    }
}
