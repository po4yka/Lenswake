package dev.po4yka.lenswake.ui

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LenswakeNavigationStateTest {
    @Test
    fun nestedDestinationKeepsItsTopLevelParentActive() {
        val (navigation, backStacks) = navigationState(LenswakeTopLevel.PROFILES)

        navigation.navigateToSetup()

        assertEquals(ProfilesRoute, navigation.activeTopLevelDestination)
        assertEquals(SetupRoute, navigation.currentDestination)
        assertEquals(
            listOf(ProfilesRoute, SetupRoute),
            backStacks.getValue(LenswakeTopLevel.PROFILES),
        )

        navigation.navigateBack()
        assertEquals(ProfilesRoute, navigation.currentDestination)
        assertEquals(ProfilesRoute, navigation.activeTopLevelDestination)
    }

    @Test
    fun topLevelNavigationPreservesIndependentNestedHistory() {
        val (navigation, backStacks) = navigationState(LenswakeTopLevel.PROFILES)
        navigation.navigateToSetup()

        navigation.navigateToTopLevel(LenswakeTopLevel.DIAGNOSTICS)
        assertEquals(DiagnosticsRoute, navigation.currentDestination)
        navigation.navigateToTopLevel(LenswakeTopLevel.PROFILES)

        assertEquals(SetupRoute, navigation.currentDestination)
        assertEquals(
            listOf(ProfilesRoute, SetupRoute),
            backStacks.getValue(LenswakeTopLevel.PROFILES),
        )
        assertEquals(
            listOf(DiagnosticsRoute),
            backStacks.getValue(LenswakeTopLevel.DIAGNOSTICS),
        )
    }

    @Test
    fun siblingTopLevelDestinationsDoNotAccumulate() {
        val (navigation, backStacks) = navigationState()

        navigation.navigateToTopLevel(LenswakeTopLevel.PROFILES)
        navigation.navigateToTopLevel(LenswakeTopLevel.DIAGNOSTICS)

        assertEquals(listOf(SchedulesRoute), backStacks.getValue(LenswakeTopLevel.SCHEDULES))
        assertEquals(listOf(ProfilesRoute), backStacks.getValue(LenswakeTopLevel.PROFILES))
        assertEquals(listOf(DiagnosticsRoute), backStacks.getValue(LenswakeTopLevel.DIAGNOSTICS))
    }

    @Test
    fun repeatedNestedNavigationIsIdempotent() {
        val (navigation, backStacks) = navigationState()

        navigation.navigateToSetup()
        navigation.navigateToSetup()

        assertEquals(
            listOf(SchedulesRoute, SetupRoute),
            backStacks.getValue(LenswakeTopLevel.SCHEDULES),
        )
    }

    @Test
    fun backFromAnotherTopLevelRootReturnsToStartRoot() {
        val (navigation, _) = navigationState(LenswakeTopLevel.DIAGNOSTICS)

        navigation.navigateBack()

        assertEquals(LenswakeTopLevel.SCHEDULES, navigation.activeTopLevel)
        assertEquals(SchedulesRoute, navigation.currentDestination)
    }

    private fun navigationState(
        selected: LenswakeTopLevel = LenswakeTopLevel.SCHEDULES,
    ): Pair<LenswakeNavigationState, Map<LenswakeTopLevel, MutableList<NavKey>>> {
        val backStacks = mapOf(
            LenswakeTopLevel.SCHEDULES to mutableListOf<NavKey>(SchedulesRoute),
            LenswakeTopLevel.PROFILES to mutableListOf<NavKey>(ProfilesRoute),
            LenswakeTopLevel.DIAGNOSTICS to mutableListOf<NavKey>(DiagnosticsRoute),
        )
        return LenswakeNavigationState(mutableStateOf(selected), backStacks) to backStacks
    }
}
