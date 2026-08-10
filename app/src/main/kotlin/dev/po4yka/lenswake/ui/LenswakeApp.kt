package dev.po4yka.lenswake.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.po4yka.lenswake.ui.screen.DiagnosticsScreen
import dev.po4yka.lenswake.ui.screen.ProfilesScreen
import dev.po4yka.lenswake.ui.screen.SchedulesScreen
import dev.po4yka.lenswake.ui.screen.SetupScreen
import dev.po4yka.lenswake.core.SetupRemediationAction
import kotlinx.serialization.Serializable

@Serializable
private data object SchedulesRoute : NavKey

@Serializable
private data object ProfilesRoute : NavKey

@Serializable
private data object DiagnosticsRoute : NavKey

@Serializable
private data object SetupRoute : NavKey

private data class TopLevelDestination(
    val key: NavKey,
    val label: String,
    val glyph: String,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(SchedulesRoute, "Schedules", "S"),
    TopLevelDestination(ProfilesRoute, "Profiles", "P"),
    TopLevelDestination(DiagnosticsRoute, "Diagnostics", "D"),
)

@Composable
fun LenswakeApp(
    viewModel: LenswakeViewModel,
    onRemediate: (SetupRemediationAction) -> Unit,
    onOpenPixelCamera: () -> Unit,
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPreflight()
        onPauseOrDispose { }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LenswakeApp(
        state = state,
        onInstallCandidateProfile = viewModel::installCandidateProfile,
        onRunRehearsal = viewModel::runRehearsal,
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
        onClearRemediationMessage = viewModel::clearSetupRemediationMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LenswakeApp(
    state: LenswakeUiState,
    onInstallCandidateProfile: () -> Unit = {},
    onRunRehearsal: () -> Unit = {},
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
    onClearRemediationMessage: () -> Unit = {},
) {
    val backStack = rememberNavBackStack(SchedulesRoute)
    val currentDestination = backStack.lastOrNull()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= NavigationRailMinWidth

        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                NavigationRail {
                    topLevelDestinations.forEach { destination ->
                        NavigationRailItem(
                            selected = currentDestination == destination.key,
                            onClick = {
                                if (currentDestination != destination.key) {
                                    backStack.add(destination.key)
                                }
                            },
                            icon = { Text(destination.glyph) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (!useNavigationRail) {
                        NavigationBar {
                            topLevelDestinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentDestination == destination.key,
                                    onClick = {
                                        if (currentDestination != destination.key) {
                                            backStack.add(destination.key)
                                        }
                                    },
                                    icon = { Text(destination.glyph) },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                },
            ) { contentPadding ->
                NavDisplay(
                    modifier = Modifier.fillMaxSize(),
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = entryProvider {
                        entry<SchedulesRoute> {
                            SchedulesScreen(
                                state = state,
                                contentPadding = contentPadding,
                                onOpenSetup = { backStack.add(SetupRoute) },
                                onBeginCreate = onBeginCreateSchedule,
                                onBeginEdit = onBeginEditSchedule,
                                onUpdateForm = onUpdateScheduleForm,
                                onSubmit = onSubmitSchedule,
                                onCancelEditor = onCancelScheduleEditor,
                                onSetEnabled = onSetScheduleEnabled,
                                onRequestDelete = onRequestDeleteSchedule,
                                onCancelDelete = onCancelDeleteSchedule,
                                onConfirmDelete = onConfirmDeleteSchedule,
                                onClearOutcome = onClearScheduleOutcome,
                            )
                        }
                        entry<ProfilesRoute> {
                            ProfilesScreen(
                                state = state,
                                contentPadding = contentPadding,
                                onOpenSetup = { backStack.add(SetupRoute) },
                                onInstallCandidateProfile = onInstallCandidateProfile,
                                onRunRehearsal = onRunRehearsal,
                            )
                        }
                        entry<DiagnosticsRoute> {
                            DiagnosticsScreen(
                                state = state,
                                contentPadding = contentPadding,
                                onOpenSetup = { backStack.add(SetupRoute) },
                                onOpenPixelCamera = onOpenPixelCamera,
                            )
                        }
                        entry<SetupRoute> {
                            SetupScreen(
                                state = state,
                                contentPadding = contentPadding,
                                onBack = { backStack.removeLastOrNull() },
                                onRemediate = onRemediate,
                                onClearRemediationMessage = onClearRemediationMessage,
                            )
                        }
                    },
                )
            }
        }
    }
}

private val NavigationRailMinWidth = 600.dp
