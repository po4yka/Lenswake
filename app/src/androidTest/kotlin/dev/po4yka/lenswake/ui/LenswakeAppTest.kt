package dev.po4yka.lenswake.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import dev.po4yka.lenswake.ui.theme.LenswakeTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LenswakeAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setContent() {
        composeRule.setContent {
            LenswakeTheme {
                LenswakeApp(state = LenswakeUiState())
            }
        }
    }

    @Test
    fun freshInstallationShowsBlockedHonestScheduleState() {
        composeRule.onNodeWithText("Setup required").assertExists()
        composeRule.onNodeWithText("No schedules").assertExists()
        composeRule.onNodeWithText("Create schedule").assertIsNotEnabled()
        composeRule.onNodeWithText("Ready").assertDoesNotExist()
    }

    @Test
    fun navigationExposesProfilesDiagnosticsAndSetupRoutes() {
        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("No verified profiles").assertExists()

        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(9)
        composeRule.onNodeWithText("No diagnostic events").assertExists()

        composeRule.onNodeWithText("Schedules").performClick()
        composeRule.onNodeWithText("Review setup").performClick()
        composeRule.onNodeWithText("Readiness checks").assertExists()
    }
}
