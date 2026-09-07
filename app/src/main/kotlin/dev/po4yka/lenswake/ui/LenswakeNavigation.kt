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

@Serializable
internal data object ScheduleEditorRoute : LenswakeRoute

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

    /**
     * The schedule editor is a nested destination of Schedules, so its route belongs to that stack
     * even when the editor state changes while another section is selected.
     */
    fun showScheduleEditor() {
        val schedules = backStacks.getValue(LenswakeTopLevel.SCHEDULES)
        if (ScheduleEditorRoute !in schedules) {
            schedules.add(ScheduleEditorRoute)
        }
    }

    fun hideScheduleEditor() {
        backStacks.getValue(LenswakeTopLevel.SCHEDULES).remove(ScheduleEditorRoute)
    }

    /**
     * Leave the current destination. The editor route mirrors the editor state, so leaving it must
     * discard the draft; popping the route alone would strand an editor no screen renders any more.
     */
    fun navigateBackFrom(onCancelScheduleEditor: () -> Unit) {
        if (currentDestination == ScheduleEditorRoute) onCancelScheduleEditor() else navigateBack()
    }

    /**
     * True at the root of a non-start section. `NavDisplay` only handles back while its stack has
     * more than one entry, so this case needs its own handler or the press escapes to the system.
     */
    val isAtNonStartRoot: Boolean
        get() = activeBackStack.size == 1 && activeTopLevel != startTopLevel

    fun returnToStartTopLevel() {
        selectedTopLevel.value = startTopLevel
    }

    fun navigateBack() {
        when {
            activeBackStack.size > 1 -> activeBackStack.removeLastOrNull()
            activeTopLevel != startTopLevel -> selectedTopLevel.value = startTopLevel
            // At the start root the system owns the gesture; never empty the back stack.
            else -> Unit
        }
    }
}
