package dev.po4yka.lenswake.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.ui.LenswakeUiState
import dev.po4yka.lenswake.ui.ActiveSessionKind
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
    val activeRehearsal = state.activeSession?.takeIf { it.kind == ActiveSessionKind.REHEARSAL }
    val rehearsalInProgress = state.rehearsal is RehearsalActionUiState.Running
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
                title = stringResource(R.string.nav_profiles),
                summary = stringResource(R.string.screen_profiles_summary),
            )
        }
        item {
            ActionSection(
                title = stringResource(R.string.profiles_camera_profile_title),
                detail = if (state.profiles.isEmpty()) {
                    stringResource(R.string.profiles_install_first_detail)
                } else {
                    stringResource(R.string.profiles_install_update_detail)
                },
                actionLabel = if (state.profileInstall is ProfileInstallUiState.Installing) {
                    stringResource(R.string.profiles_installing)
                } else {
                    stringResource(R.string.action_install_camera_profile)
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
                    title = stringResource(R.string.profiles_installed_title),
                    detail = install.message,
                    status = stringResource(R.string.status_needs_test),
                )
            }

            is ProfileInstallUiState.Failed -> item {
                SummaryCard(
                    title = stringResource(R.string.profiles_install_failed_title),
                    detail = install.message,
                    status = stringResource(R.string.status_failed),
                )
            }
        }
        item {
            ActionSection(
                title = stringResource(R.string.profiles_test_title),
                detail = activeRehearsal?.detail ?: if (state.rehearsal is RehearsalActionUiState.Running) {
                    stringResource(R.string.profiles_test_running_detail)
                } else {
                    stringResource(R.string.profiles_test_detail)
                },
                actionLabel = if (rehearsalInProgress) {
                    stringResource(R.string.profiles_testing)
                } else {
                    stringResource(R.string.action_test_recording)
                },
                actionEnabled = state.actions.canRunRehearsal,
                actionInProgress = rehearsalInProgress,
                unavailableReason = state.actions.rehearsalUnavailableReason,
                onAction = onRunRehearsal,
            )
        }
        activeRehearsal?.let { active ->
            item(key = "active-rehearsal-${active.sessionId}") {
                SummaryCard(
                    title = stringResource(R.string.profiles_active_rehearsal_title),
                    detail = active.detail,
                    status = active.status,
                )
            }
        }
        when (val rehearsal = state.rehearsal) {
            RehearsalActionUiState.Idle -> Unit
            RehearsalActionUiState.Running -> Unit
            is RehearsalActionUiState.Passed -> item {
                SummaryCard(
                    title = stringResource(R.string.profiles_test_passed_title),
                    detail = rehearsal.message,
                    status = stringResource(R.string.status_passed),
                )
            }
            is RehearsalActionUiState.Failed -> item {
                SummaryCard(
                    title = stringResource(R.string.profiles_test_failed_title),
                    detail = rehearsal.message,
                    status = stringResource(R.string.status_failed),
                )
            }
            is RehearsalActionUiState.SafetyStopPending -> item {
                SummaryCard(
                    title = stringResource(R.string.profiles_waiting_for_stop_title),
                    detail = rehearsal.message,
                    status = stringResource(R.string.status_safety_alarm_armed),
                )
            }
        }
    }
}
