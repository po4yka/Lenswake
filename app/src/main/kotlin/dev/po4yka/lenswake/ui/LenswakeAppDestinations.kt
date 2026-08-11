package dev.po4yka.lenswake.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.po4yka.lenswake.ui.screen.DiagnosticsScreen
import dev.po4yka.lenswake.ui.screen.ProfilesScreen
import dev.po4yka.lenswake.ui.screen.SchedulesScreen
import dev.po4yka.lenswake.ui.screen.SetupScreen

@Composable
internal fun LenswakeNavigationHost(
    state: LenswakeUiState,
    actions: LenswakeAppActions,
    navigation: LenswakeNavigationState,
    contentPadding: PaddingValues,
) {
    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = navigation.activeBackStack,
        onBack = navigation::navigateBack,
        entryProvider = entryProvider {
            entry<SchedulesRoute> {
                ScheduleDestination(state, actions.schedules, navigation, contentPadding)
            }
            entry<ProfilesRoute> {
                ProfilesScreen(
                    state = state,
                    contentPadding = contentPadding,
                    onInstallCandidateProfile = actions.profiles.onInstallCandidateProfile,
                    onRunRehearsal = actions.profiles.onRunRehearsal,
                )
            }
            entry<DiagnosticsRoute> {
                DiagnosticsScreen(
                    state = state,
                    contentPadding = contentPadding,
                    onOpenPixelCamera = actions.diagnostics.onOpenPixelCamera,
                    onExportDiagnostics = actions.diagnostics.onExportDiagnostics,
                )
            }
            entry<SetupRoute> {
                SetupScreen(
                    state = state,
                    contentPadding = contentPadding,
                    onRemediate = actions.setup.onRemediate,
                    onClearRemediationMessage = actions.setup.onClearRemediationMessage,
                )
            }
        },
    )
}

@Composable
private fun ScheduleDestination(
    state: LenswakeUiState,
    actions: ScheduleActions,
    navigation: LenswakeNavigationState,
    contentPadding: PaddingValues,
) {
    SchedulesScreen(
        state = state,
        contentPadding = contentPadding,
        onOpenSetup = navigation::navigateToSetup,
        onBeginCreate = actions.editor.onBeginCreate,
        onBeginEdit = actions.editor.onBeginEdit,
        onRunRehearsal = actions.onRunRehearsal,
        onUpdateForm = actions.editor.onUpdateForm,
        onSubmit = actions.editor.onSubmit,
        onCancelEditor = actions.editor.onCancel,
        onSetEnabled = actions.items.onSetEnabled,
        onRequestDelete = actions.items.onRequestDelete,
        onCancelDelete = actions.items.onCancelDelete,
        onConfirmDelete = actions.items.onConfirmDelete,
        onClearOutcome = actions.items.onClearOutcome,
    )
}
