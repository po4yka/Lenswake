package dev.po4yka.lenswake.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.ui.theme.LenswakeTheme
import java.time.LocalDateTime
import java.time.ZoneId
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
        onSubmitSchedule: () -> Unit = {},
        onRemediate: (SetupRemediationAction) -> Unit = {},
    ) {
        composeRule.setContent {
            LenswakeTheme {
                LenswakeApp(
                    state = state,
                    onInstallCandidateProfile = onInstallCandidateProfile,
                    onRunRehearsal = onRunRehearsal,
                    onSubmitSchedule = onSubmitSchedule,
                    onRemediate = onRemediate,
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
    fun readinessSummaryAppearsOnlyWhereItIsActionable() {
        setContent()
        composeRule.onNodeWithText("Setup required").assertExists()

        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("Setup required").assertDoesNotExist()

        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNodeWithText("Setup required").assertDoesNotExist()

        composeRule.onNodeWithText("Schedules").performClick()
        composeRule.onNodeWithText("Review setup").performClick()
        composeRule.onNodeWithText("Setup required").assertExists()
    }

    @Test
    fun navigationAndStatusesUseAccessibleIconsInsteadOfTextGlyphs() {
        setContent()

        listOf("S", "P", "D", "!", "△", "✓", "?", "•").forEach { glyph ->
            composeRule.onNodeWithText(glyph).assertDoesNotExist()
        }
        composeRule.onNodeWithContentDescription("Blocked status").assertExists()
    }

    @Test
    fun nestedSetupStaysScopedToItsSelectedTopLevelBackStack() {
        setContent()
        val profilesNavigation = composeRule.onNode(hasText("Profiles") and hasClickAction())
        val schedulesNavigation = composeRule.onNode(hasText("Schedules") and hasClickAction())
        composeRule.onNodeWithText("Review setup").performClick()

        composeRule.onNodeWithText("Readiness checks").assertExists()
        schedulesNavigation.assertIsSelected()

        profilesNavigation.performClick()
        composeRule.onNodeWithText("No profiles").assertExists()
        profilesNavigation.assertIsSelected()

        schedulesNavigation.performClick()
        composeRule.onNodeWithText("Readiness checks").assertExists()
        schedulesNavigation.assertIsSelected()

        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("No schedules").assertExists()
    }

    @Test
    fun setupDispatchesTypedRemediationFromFailedCapability() {
        var dispatched: SetupRemediationAction? = null
        setContent(
            state = LenswakeUiState(
                capabilities = listOf(
                    CapabilityUiState(
                        name = "Notifications",
                        status = CapabilityStatus.BLOCKED,
                        detail = "Notification permission is required.",
                        required = true,
                        remediation = SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION,
                    ),
                ),
            ),
            onRemediate = { dispatched = it },
        )

        composeRule.onNodeWithText("Review setup").performClick()
        composeRule.onNodeWithText("Resolve").performClick()
        composeRule.runOnIdle {
            assertEquals(SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION, dispatched)
        }
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
                        verifiedForScheduling = false,
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

    @Test
    fun schedulesRouteRendersRealVerifiedProfileFormAndDispatchesSubmit() {
        var submitRequests = 0
        setContent(
            state = LenswakeUiState(
                profiles = listOf(
                    ProfileSummaryUiState(
                        id = "profile-verified",
                        title = "Pixel 8 Pro",
                        environment = "Android 17 - Pixel Camera 69481630 - en-US",
                        compatibility = "Persisted as verified; see current compatibility in Setup",
                        verifiedForScheduling = true,
                    ),
                ),
                scheduleEditor = ScheduleEditorUiState.Open(
                    mode = ScheduleEditorMode.Create,
                    form = ScheduleFormUiState(
                        name = "Dawn",
                        startLocal = LocalDateTime.of(2030, 1, 1, 6, 0),
                        stopLocal = LocalDateTime.of(2030, 1, 1, 8, 0),
                        zoneId = ZoneId.of("Asia/Tbilisi"),
                        profileId = "profile-verified",
                    ),
                ),
                actions = UiActionAvailability(canCreateSchedule = true),
            ),
            onSubmitSchedule = { submitRequests += 1 },
        )

        composeRule.onNodeWithText("Save schedule").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, submitRequests) }
        composeRule.onNodeWithText("120×", substring = true).assertExists()
    }

    @Test
    fun scheduleEditorUsesGuidedDateAndTimeControls() {
        setContent(
            state = LenswakeUiState(
                profiles = listOf(
                    ProfileSummaryUiState(
                        id = "profile-verified",
                        title = "Pixel 8 Pro",
                        environment = "Android 17 - Pixel Camera 69481630 - en-US",
                        compatibility = "Verified",
                        verifiedForScheduling = true,
                    ),
                ),
                scheduleEditor = ScheduleEditorUiState.Open(
                    mode = ScheduleEditorMode.Create,
                    form = ScheduleFormUiState(
                        name = "Time Lapse",
                        startLocal = LocalDateTime.of(2030, 1, 1, 6, 0),
                        stopLocal = LocalDateTime.of(2030, 1, 1, 8, 0),
                        zoneId = ZoneId.of("Asia/Tbilisi"),
                        profileId = "profile-verified",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription("Choose start date", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Choose start time", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Choose end date", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Choose end time", substring = true).assertExists()
        composeRule.onNodeWithText("Time zone").assertExists()
        composeRule.onNodeWithText("Start local time").assertDoesNotExist()
        composeRule.onNodeWithText("IANA time zone").assertDoesNotExist()
        composeRule.onNodeWithText("YYYY-MM-DDTHH:MM", substring = true).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Choose start date", substring = true).performScrollTo()
        composeRule.onNodeWithContentDescription("Choose start date", substring = true).performClick()
        composeRule.onNodeWithText("Use date").assertExists()
        composeRule.onNodeWithText("Use date").performClick()
        composeRule.onNodeWithContentDescription("Choose start time", substring = true).performScrollTo().performClick()
        composeRule.onNodeWithText("Use time").assertExists()
    }
}
