package dev.po4yka.lenswake.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.SetupRemediationAction

internal data class TopLevelDestination(
    val topLevel: LenswakeTopLevel,
    @param:StringRes val labelResource: Int,
    @param:DrawableRes val iconResource: Int,
) {
    val key: LenswakeRoute
        get() = topLevel.route
}

internal val topLevelDestinations = listOf(
    TopLevelDestination(LenswakeTopLevel.SCHEDULES, R.string.nav_schedules, R.drawable.ic_schedule_24),
    TopLevelDestination(LenswakeTopLevel.PROFILES, R.string.nav_profiles, R.drawable.ic_tune_24),
    TopLevelDestination(LenswakeTopLevel.DIAGNOSTICS, R.string.nav_diagnostics, R.drawable.ic_diagnostics_24),
)

@Composable
fun LenswakeApp(
    viewModel: LenswakeViewModel,
    onRemediate: (SetupRemediationAction) -> Unit,
    onOpenPixelCamera: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPreflight()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LenswakeApp(
        state = state,
        onInstallCandidateProfile = viewModel::installCandidateProfile,
        onRunRehearsal = viewModel::runProfileRehearsal,
        onRunScheduleRehearsal = viewModel::runScheduleRehearsal,
        onBeginCreateSchedule = viewModel::beginCreateSchedule,
        onBeginEditSchedule = viewModel::beginEditSchedule,
        onUpdateScheduleForm = viewModel::updateScheduleForm,
        onSubmitSchedule = viewModel::submitSchedule,
        onCancelScheduleEditor = viewModel::cancelScheduleEditor,
        onSetScheduleEnabled = viewModel::setScheduleEnabled,
        onRequestDeleteSchedule = viewModel::requestDeleteSchedule,
        onCancelDeleteSchedule = viewModel::cancelDeleteSchedule,
        onConfirmDeleteSchedule = viewModel::confirmDeleteSchedule,
        onClearScheduleOutcome = viewModel::clearScheduleOutcome,
        onRemediate = onRemediate,
        onOpenPixelCamera = onOpenPixelCamera,
        onExportDiagnostics = onExportDiagnostics,
        onClearRemediationMessage = viewModel::clearSetupRemediationMessage,
    )
}

@Composable
fun LenswakeApp(
    state: LenswakeUiState,
    onInstallCandidateProfile: () -> Unit = {},
    onRunRehearsal: (String) -> Unit = {},
    onRunScheduleRehearsal: (String) -> Unit = {},
    onBeginCreateSchedule: () -> Unit = {},
    onBeginEditSchedule: (String) -> Unit = {},
    onUpdateScheduleForm: (ScheduleFormUiState) -> Unit = {},
    onSubmitSchedule: () -> Unit = {},
    onCancelScheduleEditor: () -> Unit = {},
    onSetScheduleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onRequestDeleteSchedule: (String) -> Unit = {},
    onCancelDeleteSchedule: () -> Unit = {},
    onConfirmDeleteSchedule: (String) -> Unit = {},
    onClearScheduleOutcome: () -> Unit = {},
    onRemediate: (SetupRemediationAction) -> Unit = {},
    onOpenPixelCamera: () -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
    onClearRemediationMessage: () -> Unit = {},
) {
    val actions = LenswakeAppActions(
        profiles = ProfileActions(onInstallCandidateProfile, onRunRehearsal),
        schedules = ScheduleActions(
            onRunRehearsal = onRunScheduleRehearsal,
            editor = ScheduleEditorActions(
                onBeginCreateSchedule,
                onBeginEditSchedule,
                onUpdateScheduleForm,
                onSubmitSchedule,
                onCancelScheduleEditor,
            ),
            items = ScheduleItemActions(
                onSetScheduleEnabled,
                onRequestDeleteSchedule,
                onCancelDeleteSchedule,
                onConfirmDeleteSchedule,
                onClearScheduleOutcome,
            ),
        ),
        setup = SetupActions(onRemediate, onClearRemediationMessage),
        diagnostics = DiagnosticsActions(onOpenPixelCamera, onExportDiagnostics),
    )
    LenswakeApp(state, actions)
}

@Composable
private fun LenswakeApp(
    state: LenswakeUiState,
    actions: LenswakeAppActions,
) {
    val navigation = rememberLenswakeNavigationState()
    LenswakeAdaptiveLayout(state, actions, navigation)
}

internal data class LenswakeAppActions(
    val profiles: ProfileActions,
    val schedules: ScheduleActions,
    val setup: SetupActions,
    val diagnostics: DiagnosticsActions,
)

internal data class ProfileActions(
    val onInstallCandidateProfile: () -> Unit,
    val onRunRehearsal: (String) -> Unit,
)

internal data class ScheduleActions(
    val onRunRehearsal: (String) -> Unit,
    val editor: ScheduleEditorActions,
    val items: ScheduleItemActions,
)

internal data class ScheduleEditorActions(
    val onBeginCreate: () -> Unit,
    val onBeginEdit: (String) -> Unit,
    val onUpdateForm: (ScheduleFormUiState) -> Unit,
    val onSubmit: () -> Unit,
    val onCancel: () -> Unit,
)

internal data class ScheduleItemActions(
    val onSetEnabled: (String, Boolean) -> Unit,
    val onRequestDelete: (String) -> Unit,
    val onCancelDelete: () -> Unit,
    val onConfirmDelete: (String) -> Unit,
    val onClearOutcome: () -> Unit,
)

internal data class SetupActions(
    val onRemediate: (SetupRemediationAction) -> Unit,
    val onClearRemediationMessage: () -> Unit,
)

internal data class DiagnosticsActions(
    val onOpenPixelCamera: () -> Unit,
    val onExportDiagnostics: () -> Unit,
)

internal enum class AdaptiveNavigationLayout {
    BOTTOM_BAR,
    RAIL,
    DRAWER,
}

internal fun adaptiveNavigationLayout(width: Dp): AdaptiveNavigationLayout = when {
    width < MediumWindowMinWidth -> AdaptiveNavigationLayout.BOTTOM_BAR
    width < ExpandedWindowMinWidth -> AdaptiveNavigationLayout.RAIL
    else -> AdaptiveNavigationLayout.DRAWER
}

internal const val NAVIGATION_BAR_TAG = "lenswake-navigation-bar"
internal const val NAVIGATION_RAIL_TAG = "lenswake-navigation-rail"
internal const val NAVIGATION_DRAWER_TAG = "lenswake-navigation-drawer"
internal const val SETUP_TOP_APP_BAR_TAG = "lenswake-setup-top-app-bar"

private val MediumWindowMinWidth = 600.dp
private val ExpandedWindowMinWidth = 840.dp
