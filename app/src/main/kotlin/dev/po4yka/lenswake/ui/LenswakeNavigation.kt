package dev.po4yka.lenswake.ui

import androidx.compose.runtime.MutableState
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

internal sealed interface LenswakeRoute : NavKey

@Serializable
internal data object SchedulesRoute : LenswakeRoute

@Serializable
internal data object ProfilesRoute : LenswakeRoute

@Serializable
internal data object DiagnosticsRoute : LenswakeRoute

@Serializable
internal data object SetupRoute : LenswakeRoute

internal enum class LenswakeTopLevel(
    val route: LenswakeRoute,
) {
    SCHEDULES(SchedulesRoute),
    PROFILES(ProfilesRoute),
    DIAGNOSTICS(DiagnosticsRoute),
}

internal class LenswakeNavigationState(
    private val selectedTopLevel: MutableState<LenswakeTopLevel>,
    private val backStacks: Map<LenswakeTopLevel, MutableList<NavKey>>,
    private val startTopLevel: LenswakeTopLevel = LenswakeTopLevel.SCHEDULES,
) {
    init {
        require(backStacks.keys.containsAll(LenswakeTopLevel.entries)) {
            "Every top-level destination requires its own back stack."
        }
        require(backStacks.all { (topLevel, stack) -> stack.firstOrNull() == topLevel.route }) {
            "Every top-level back stack must start with its own root route."
        }
    }

    val activeTopLevel: LenswakeTopLevel
        get() = selectedTopLevel.value

    val activeBackStack: MutableList<NavKey>
        get() = backStacks.getValue(activeTopLevel)

    val currentDestination: NavKey?
        get() = activeBackStack.lastOrNull()

    val activeTopLevelDestination: NavKey?
        get() = activeTopLevel.route

    fun navigateToTopLevel(destination: LenswakeTopLevel) {
        selectedTopLevel.value = destination
    }

    fun navigateToSetup() {
        if (currentDestination != SetupRoute) {
            activeBackStack.add(SetupRoute)
        }
    }

    fun navigateBack() {
        when {
            activeBackStack.size > 1 -> activeBackStack.removeLastOrNull()
            activeTopLevel != startTopLevel -> selectedTopLevel.value = startTopLevel
            else -> activeBackStack.removeLastOrNull()
        }
    }
}
