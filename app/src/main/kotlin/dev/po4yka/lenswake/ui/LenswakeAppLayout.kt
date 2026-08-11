package dev.po4yka.lenswake.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.rememberNavBackStack
import dev.po4yka.lenswake.R

@Composable
internal fun rememberLenswakeNavigationState(): LenswakeNavigationState {
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
    return remember(selectedTopLevel, backStacks) {
        LenswakeNavigationState(selectedTopLevel, backStacks)
    }
}

@Composable
internal fun LenswakeAdaptiveLayout(
    state: LenswakeUiState,
    actions: LenswakeAppActions,
    navigation: LenswakeNavigationState,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val navigationLayout = adaptiveNavigationLayout(maxWidth)
        when (navigationLayout) {
            AdaptiveNavigationLayout.BOTTOM_BAR -> LenswakeScaffold(
                state,
                actions,
                navigation,
                navigationLayout,
                Modifier.fillMaxSize(),
            )
            AdaptiveNavigationLayout.RAIL -> LenswakeRailLayout(state, actions, navigation)
            AdaptiveNavigationLayout.DRAWER -> LenswakeDrawerLayout(state, actions, navigation)
        }
    }
}

@Composable
private fun LenswakeRailLayout(
    state: LenswakeUiState,
    actions: LenswakeAppActions,
    navigation: LenswakeNavigationState,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        TopLevelNavigationRail(navigation)
        LenswakeScaffold(
            state,
            actions,
            navigation,
            AdaptiveNavigationLayout.RAIL,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun LenswakeDrawerLayout(
    state: LenswakeUiState,
    actions: LenswakeAppActions,
    navigation: LenswakeNavigationState,
) {
    PermanentNavigationDrawer(
        drawerContent = { TopLevelNavigationDrawer(navigation) },
        content = {
            LenswakeScaffold(
                state,
                actions,
                navigation,
                AdaptiveNavigationLayout.DRAWER,
                Modifier.fillMaxSize(),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LenswakeScaffold(
    state: LenswakeUiState,
    actions: LenswakeAppActions,
    navigation: LenswakeNavigationState,
    navigationLayout: AdaptiveNavigationLayout,
    modifier: Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { SetupTopAppBar(navigation) },
        bottomBar = {
            if (navigationLayout == AdaptiveNavigationLayout.BOTTOM_BAR) {
                TopLevelNavigationBar(navigation)
            }
        },
    ) { contentPadding ->
        LenswakeNavigationHost(state, actions, navigation, contentPadding)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupTopAppBar(navigation: LenswakeNavigationState) {
    if (navigation.currentDestination == SetupRoute) {
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
}

@Composable
private fun TopLevelNavigationBar(navigation: LenswakeNavigationState) {
    NavigationBar(modifier = Modifier.testTag(NAVIGATION_BAR_TAG)) {
        topLevelDestinations.forEach { destination ->
            NavigationBarItem(
                selected = navigation.activeTopLevelDestination == destination.key,
                onClick = { navigation.navigateToTopLevel(destination.topLevel) },
                icon = { TopLevelIcon(destination) },
                label = { Text(stringResource(destination.labelResource)) },
            )
        }
    }
}

@Composable
private fun TopLevelNavigationRail(navigation: LenswakeNavigationState) {
    NavigationRail(modifier = Modifier.testTag(NAVIGATION_RAIL_TAG)) {
        topLevelDestinations.forEach { destination ->
            NavigationRailItem(
                selected = navigation.activeTopLevelDestination == destination.key,
                onClick = { navigation.navigateToTopLevel(destination.topLevel) },
                icon = { TopLevelIcon(destination) },
                label = { Text(stringResource(destination.labelResource)) },
            )
        }
    }
}

@Composable
private fun TopLevelNavigationDrawer(navigation: LenswakeNavigationState) {
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
                selected = navigation.activeTopLevelDestination == destination.key,
                onClick = { navigation.navigateToTopLevel(destination.topLevel) },
                icon = { TopLevelIcon(destination) },
                label = { Text(stringResource(destination.labelResource)) },
                modifier = Modifier.padding(horizontal = 12.dp),
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

private val ExpandedDrawerWidth = 360.dp
