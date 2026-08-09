package dev.po4yka.lenswake.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import dev.po4yka.lenswake.ui.theme.LenswakeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class LenswakeAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: LenswakeUiState = LenswakeUiState(),
        onInstallCandidateProfile: () -> Unit = {},
        onRunRehearsal: () -> Unit = {},
    ) {
        composeRule.setContent {
            LenswakeTheme {
                LenswakeApp(
                    state = state,
                    onInstallCandidateProfile = onInstallCandidateProfile,
                    onRunRehearsal = onRunRehearsal,
                )
            }
        }
    }

    @Test
    fun freshInstallationShowsBlockedHonestScheduleState() {
        setContent()
        composeRule.onNodeWithText("Setup required").assertExists()
        composeRule.onNodeWithText("No schedules").assertExists()
        composeRule.onNodeWithText("Create schedule").assertIsNotEnabled()
        composeRule.onNodeWithText("Ready").assertDoesNotExist()
    }

    @Test
    fun navigationExposesProfilesDiagnosticsAndSetupRoutes() {
        setContent()
        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("No profiles").assertExists()

        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(9)
        composeRule.onNodeWithText("No diagnostic events").assertExists()

        composeRule.onNodeWithText("Schedules").performClick()
        composeRule.onNodeWithText("Review setup").performClick()
        composeRule.onNodeWithText("Readiness checks").assertExists()
    }

    @Test
    fun profilesRouteDispatchesCandidateInstallAndKeepsRehearsalDisabled() {
        var installRequests = 0
        setContent(
            state = LenswakeUiState(
                actions = UiActionAvailability(canInstallCandidateProfile = true),
            ),
            onInstallCandidateProfile = { installRequests += 1 },
        )

        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("Install candidate profile").performClick()
        composeRule.runOnIdle { assertEquals(1, installRequests) }
        composeRule.onNodeWithText("Run rehearsal").assertIsNotEnabled()
    }

    @Test
    fun profilesRouteRendersPersistentInstallFailure() {
        setContent(
            state = LenswakeUiState(
                profileInstall = ProfileInstallUiState.Failed("The environment does not match."),
                actions = UiActionAvailability(canInstallCandidateProfile = true),
            ),
        )

        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("Candidate profile installation failed").assertExists()
        composeRule.onNodeWithText("The environment does not match.").assertExists()
        composeRule.onNodeWithText("Run rehearsal").assertIsNotEnabled()
    }

    @Test
    fun profilesRouteDispatchesEnabledRehearsal() {
        var rehearsalRequests = 0
        setContent(
            state = LenswakeUiState(
                profiles = listOf(
                    ProfileSummaryUiState(
                        id = "profile-1",
                        title = "Pixel 8 Pro",
                        environment = "Android 17 - Pixel Camera 69481630 - en-US",
                        compatibility = "Needs rehearsal",
                    ),
                ),
                actions = UiActionAvailability(canRunRehearsal = true),
            ),
            onRunRehearsal = { rehearsalRequests += 1 },
        )

        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("Run rehearsal").performClick()
        composeRule.runOnIdle { assertEquals(1, rehearsalRequests) }
    }
}
