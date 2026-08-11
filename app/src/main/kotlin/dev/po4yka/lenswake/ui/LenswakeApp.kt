package dev.po4yka.lenswake.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val showSetupTopAppBar = navigation.currentDestination == SetupRoute

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val navigationLayout = adaptiveNavigationLayout(maxWidth)
        val appContent: @Composable (Modifier) -> Unit = { modifier ->
            Scaffold(
                modifier = modifier,
                topBar = {
                    if (showSetupTopAppBar) {
                        TopAppBar(
                            title = { Text(stringResource(R.string.screen_setup_title)) },
                            navigationIcon = {
                                IconButton(onClick = navigation::navigateBack) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back_24),
                                        contentDescription = stringResource(R.string.action_back),
                                    )
                                }
                            },
                            modifier = Modifier.testTag(SETUP_TOP_APP_BAR_TAG),
                        )
                    }
                },
                bottomBar = {
                    if (navigationLayout == AdaptiveNavigationLayout.BOTTOM_BAR) {
                        NavigationBar(modifier = Modifier.testTag(NAVIGATION_BAR_TAG)) {
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
                                onRunRehearsal = onRunScheduleRehearsal,
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
                                onExportDiagnostics = onExportDiagnostics,
                            )
                        }
                        entry<SetupRoute> {
                            SetupScreen(
                                state = state,
                                contentPadding = contentPadding,
                                onRemediate = onRemediate,
                                onClearRemediationMessage = onClearRemediationMessage,
                            )
                        }
                    },
                )
            }
        }

        when (navigationLayout) {
            AdaptiveNavigationLayout.BOTTOM_BAR -> appContent(Modifier.fillMaxSize())

            AdaptiveNavigationLayout.RAIL -> Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.testTag(NAVIGATION_RAIL_TAG)) {
                    topLevelDestinations.forEach { destination ->
                        NavigationRailItem(
                            selected = activeTopLevelDestination == destination.key,
                            onClick = { navigation.navigateToTopLevel(destination.topLevel) },
                            icon = { TopLevelIcon(destination) },
                            label = { Text(stringResource(destination.labelResource)) },
                        )
                    }
                }
                appContent(Modifier.weight(1f))
            }

            AdaptiveNavigationLayout.DRAWER -> PermanentNavigationDrawer(
                drawerContent = {
                    PermanentDrawerSheet(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(ExpandedDrawerWidth)
                            .testTag(NAVIGATION_DRAWER_TAG),
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        topLevelDestinations.forEach { destination ->
                            NavigationDrawerItem(
                                selected = activeTopLevelDestination == destination.key,
                                onClick = { navigation.navigateToTopLevel(destination.topLevel) },
                                icon = { TopLevelIcon(destination) },
                                label = { Text(stringResource(destination.labelResource)) },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                },
                content = { appContent(Modifier.fillMaxSize()) },
            )
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
private val ExpandedDrawerWidth = 360.dp
