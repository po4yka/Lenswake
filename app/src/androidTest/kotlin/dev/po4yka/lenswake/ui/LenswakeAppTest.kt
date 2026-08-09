package dev.po4yka.lenswake.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.po4yka.lenswake.ui.theme.LenswakeTheme
import org.junit.Rule
import org.junit.Test

class LenswakeAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun freshInstallationShowsBlockedHonestScheduleState() {
        composeRule.setContent {
            LenswakeTheme(dynamicColor = false) {
                LenswakeApp(state = LenswakeUiState())
            }
        }

        composeRule.onNodeWithText("Setup required").assertExists()
        composeRule.onNodeWithText("No schedules").assertExists()
        composeRule.onNodeWithText("Create schedule").assertIsNotEnabled()
        composeRule.onNodeWithText("Ready").assertDoesNotExist()
    }

    @Test
    fun navigationExposesProfilesDiagnosticsAndSetupRoutes() {
        composeRule.setContent {
            LenswakeTheme(dynamicColor = false) {
                LenswakeApp(state = LenswakeUiState())
            }
        }

        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("No verified profiles").assertExists()

        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNodeWithText("No diagnostic events").assertExists()

        composeRule.onNodeWithText("Review setup").performClick()
        composeRule.onNodeWithText("Readiness checks").assertExists()
    }
}
