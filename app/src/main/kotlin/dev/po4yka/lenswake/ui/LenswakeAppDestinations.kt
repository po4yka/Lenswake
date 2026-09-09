package dev.po4yka.lenswake.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.po4yka.lenswake.ui.screen.DiagnosticsScreen
import dev.po4yka.lenswake.ui.screen.ProfilesScreen
import dev.po4yka.lenswake.ui.screen.ScheduleEditorScreen
import dev.po4yka.lenswake.ui.screen.SchedulesScreen
import dev.po4yka.lenswake.ui.screen.SetupScreen
import dev.po4yka.lenswake.core.SetupRemediationAction

@Composable
internal fun LenswakeNavigationHost(
    state: LenswakeUiState,
    actions: LenswakeAppActions,
    navigation: LenswakeNavigationState,
    contentPadding: PaddingValues,
) {
    // NavDisplay only enables its own back handling while the stack can pop, so the root of a
    // non-start section would otherwise send the press to the system and background the task.
    BackHandler(enabled = navigation.isAtNonStartRoot) {
        navigation.returnToStartTopLevel()
    }
    ScheduleEditorRouteSync(state.scheduleEditor, navigation)
    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = navigation.activeBackStack,
        onBack = { navigation.navigateBackFrom(actions.schedules.editor.onCancel) },
        entryProvider = entryProvider {
            entry<SchedulesRoute> {
                ScheduleDestination(state, actions.schedules, navigation, contentPadding)
            }
            entry<ScheduleEditorRoute> {
                ScheduleEditorDestination(state, actions.schedules, contentPadding)
            }
            entry<ProfilesRoute> {
                ProfilesScreen(
                    state = state,
                    contentPadding = contentPadding,
                    onInstallCandidateProfile = actions.profiles.onInstallCandidateProfile,
                    onConfirmExperimentalProfileInstallation =
                        actions.profiles.onConfirmExperimentalProfileInstallation,
                    onImportReleaseCertification = actions.profiles.onImportReleaseCertification,
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
                    onRemediate = { action ->
                        if (action == SetupRemediationAction.OPEN_PROFILES) {
                            navigation.navigateToTopLevel(LenswakeTopLevel.PROFILES)
                        } else {
                            actions.setup.onRemediate(action)
                        }
                    },
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
        onBeginEdit = actions.editor.onBeginEdit,
        onRunRehearsal = actions.onRunRehearsal,
        onSetEnabled = actions.items.onSetEnabled,
        onRequestDelete = actions.items.onRequestDelete,
        onCancelDelete = actions.items.onCancelDelete,
        onConfirmDelete = actions.items.onConfirmDelete,
        onClearOutcome = actions.items.onClearOutcome,
    )
}

/**
 * Keeps the editor route in step with [ScheduleEditorUiState], which stays the single owner of the
 * form. Pushing on open and dropping on close also heals the back stack after process death, where
 * the saved route outlives the editor state.
 */
@Composable
private fun ScheduleEditorRouteSync(
    editor: ScheduleEditorUiState,
    navigation: LenswakeNavigationState,
) {
    val open = editor is ScheduleEditorUiState.Open
    LaunchedEffect(open) {
        if (open) navigation.showScheduleEditor() else navigation.hideScheduleEditor()
    }
}

@Composable
private fun ScheduleEditorDestination(
    state: LenswakeUiState,
    actions: ScheduleActions,
    contentPadding: PaddingValues,
) {
    // One frame after process death the saved route outlives the closed editor; the route sync
    // pops it, so render nothing rather than crash on the missing form.
    val editor = state.scheduleEditor as? ScheduleEditorUiState.Open ?: return
    ScheduleEditorScreen(
        editor = editor,
        profiles = state.profiles,
        contentPadding = contentPadding,
        action = state.scheduleAction,
        onUpdateForm = actions.editor.onUpdateForm,
        onSubmit = actions.editor.onSubmit,
        onCancel = actions.editor.onCancel,
        onClearOutcome = actions.items.onClearOutcome,
    )
}
