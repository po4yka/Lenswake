package dev.po4yka.lenswake.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.ui.screen.DiagnosticsScreen
import dev.po4yka.lenswake.ui.screen.ProfilesScreen
import dev.po4yka.lenswake.ui.screen.SchedulesScreen
import dev.po4yka.lenswake.ui.screen.SetupScreen

private data class TopLevelDestination(
    val topLevel: LenswakeTopLevel,
    @param:StringRes val labelResource: Int,
    @param:DrawableRes val iconResource: Int,
) {
    val key: LenswakeRoute
        get() = topLevel.route
}

private val topLevelDestinations = listOf(
    TopLevelDestination(LenswakeTopLevel.SCHEDULES, R.string.nav_schedules, R.drawable.ic_schedule_24),
    TopLevelDestination(LenswakeTopLevel.PROFILES, R.string.nav_profiles, R.drawable.ic_tune_24),
    TopLevelDestination(LenswakeTopLevel.DIAGNOSTICS, R.string.nav_diagnostics, R.drawable.ic_diagnostics_24),
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
    val selectedTopLevel = rememberSaveable { mutableStateOf(LenswakeTopLevel.SCHEDULES) }
    val schedulesBackStack = rememberNavBackStack(SchedulesRoute)
    val profilesBackStack = rememberNavBackStack(ProfilesRoute)
    val diagnosticsBackStack = rememberNavBackStack(DiagnosticsRoute)
    val backStacks = remember(schedulesBackStack, profilesBackStack, diagnosticsBackStack) {
        mapOf(
            LenswakeTopLevel.SCHEDULES to schedulesBackStack,
            LenswakeTopLevel.PROFILES to profilesBackStack,
            LenswakeTopLevel.DIAGNOSTICS to diagnosticsBackStack,
        )
    }
    val navigation = remember(selectedTopLevel, backStacks) {
        LenswakeNavigationState(selectedTopLevel, backStacks)
    }
    val backStack = navigation.activeBackStack
    val activeTopLevelDestination = navigation.activeTopLevelDestination

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= NavigationRailMinWidth

        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                NavigationRail {
                    topLevelDestinations.forEach { destination ->
                        NavigationRailItem(
                            selected = activeTopLevelDestination == destination.key,
                            onClick = { navigation.navigateToTopLevel(destination.topLevel) },
                            icon = { TopLevelIcon(destination) },
                            label = { Text(stringResource(destination.labelResource)) },
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
                                    selected = activeTopLevelDestination == destination.key,
                                    onClick = { navigation.navigateToTopLevel(destination.topLevel) },
                                    icon = { TopLevelIcon(destination) },
                                    label = { Text(stringResource(destination.labelResource)) },
                                )
                            }
                        }
                    }
                },
            ) { contentPadding ->
                NavDisplay(
                    modifier = Modifier.fillMaxSize(),
                    backStack = backStack,
                    onBack = navigation::navigateBack,
                    entryProvider = entryProvider {
                        entry<SchedulesRoute> {
                            SchedulesScreen(
                                state = state,
                                contentPadding = contentPadding,
                                onOpenSetup = navigation::navigateToSetup,
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
                                onInstallCandidateProfile = onInstallCandidateProfile,
                                onRunRehearsal = onRunRehearsal,
                            )
                        }
                        entry<DiagnosticsRoute> {
                            DiagnosticsScreen(
                                state = state,
                                contentPadding = contentPadding,
                                onOpenPixelCamera = onOpenPixelCamera,
                            )
                        }
                        entry<SetupRoute> {
                            SetupScreen(
                                state = state,
                                contentPadding = contentPadding,
                                onBack = navigation::navigateBack,
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

@Composable
private fun TopLevelIcon(destination: TopLevelDestination) {
    Icon(
        painter = painterResource(destination.iconResource),
        contentDescription = null,
    )
}

private val NavigationRailMinWidth = 600.dp
