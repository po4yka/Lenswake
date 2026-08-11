package dev.po4yka.lenswake.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalContext
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.ui.theme.LenswakeTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class LenswakeAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: LenswakeUiState? = null,
        onInstallCandidateProfile: () -> Unit = {},
        onRunRehearsal: () -> Unit = {},
        onUpdateScheduleForm: (ScheduleFormUiState) -> Unit = {},
        onSubmitSchedule: () -> Unit = {},
        onRequestDeleteSchedule: (String) -> Unit = {},
        onCancelDeleteSchedule: () -> Unit = {},
        onConfirmDeleteSchedule: (String) -> Unit = {},
        onRemediate: (SetupRemediationAction) -> Unit = {},
        onExportDiagnostics: () -> Unit = {},
    ) {
        composeRule.setContent {
            LenswakeTheme {
                val resolvedState = state ?: LenswakeUiStateMapper.initial(
                    AndroidUiStringProvider(LocalContext.current),
                )
                LenswakeApp(
                    state = resolvedState,
                    onInstallCandidateProfile = onInstallCandidateProfile,
                    onRunRehearsal = onRunRehearsal,
                    onUpdateScheduleForm = onUpdateScheduleForm,
                    onSubmitSchedule = onSubmitSchedule,
                    onRequestDeleteSchedule = onRequestDeleteSchedule,
                    onCancelDeleteSchedule = onCancelDeleteSchedule,
                    onConfirmDeleteSchedule = onConfirmDeleteSchedule,
                    onRemediate = onRemediate,
                    onExportDiagnostics = onExportDiagnostics,
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
        composeRule.onNodeWithText("Camera profile").assertExists()

        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNodeWithText("No activity yet").assertExists()
        composeRule.onNodeWithText("Export diagnostics").assertDoesNotExist()

        composeRule.onNodeWithText("Schedules").performClick()
        composeRule.onNodeWithText("Review setup").performClick()
        composeRule.onNodeWithText("Readiness checks").assertExists()
    }

    @Test
    fun diagnosticsPrioritizesAttentionAndActivityOverSetupChecks() {
        setContent(
            state = LenswakeUiState(
                capabilities = listOf(
                    CapabilityUiState(
                        name = "Hidden setup check",
                        status = CapabilityStatus.BLOCKED,
                        detail = "This belongs in Setup.",
                        required = true,
                    ),
                ),
                alarmTransportIncidents = listOf(
                    AlarmTransportIncidentUiState(
                        id = "alarm-1",
                        title = "Scheduled STOP needs manual action",
                        detail = "Open Pixel Camera and stop recording.",
                        occurredAt = "08:30",
                    ),
                ),
                profilePersistenceIssues = listOf(
                    ProfilePersistenceIssueUiState(
                        id = "profile-1",
                        title = "Camera profile storage issue",
                        detail = "A stored profile could not be read.",
                    ),
                ),
                diagnosticEvents = listOf(
                    DiagnosticEventUiState(
                        id = "event-1",
                        title = "automation.record.stop_verified",
                        detail = "Completed",
                        occurredAt = "08:31",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Diagnostics").performClick()

        composeRule.onNodeWithText("Needs attention").assertExists()
        composeRule.onNodeWithText("Hidden setup check").assertDoesNotExist()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        composeRule.onNodeWithText("Activity").assertExists()
        composeRule.onNodeWithText("automation.record.stop_verified").assertExists()
        composeRule.onNodeWithText("Recorded event").assertDoesNotExist()
    }

    @Test
    fun diagnosticsExportDispatchesWhenActivityExists() {
        var exportRequests = 0
        setContent(
            state = LenswakeUiState(
                diagnosticEvents = listOf(
                    DiagnosticEventUiState(
                        id = "event-1",
                        title = "automation.record.stop_verified",
                        detail = "Completed",
                        occurredAt = "08:31",
                    ),
                ),
                actions = UiActionAvailability(canExportDiagnostics = true),
            ),
            onExportDiagnostics = { exportRequests += 1 },
        )

        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNodeWithText("Export diagnostics").performClick()

        composeRule.runOnIdle { assertEquals(1, exportRequests) }
    }

    @Test
    fun setupUsesTopAppBarBackNavigation() {
        setContent()

        composeRule.onNodeWithText("Review setup").performClick()

        composeRule.onNodeWithTag(SETUP_TOP_APP_BAR_TAG).assertExists()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag(SETUP_TOP_APP_BAR_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("No schedules").assertExists()
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
        composeRule.onNodeWithText("Camera profile").assertExists()
        profilesNavigation.assertIsSelected()

        schedulesNavigation.performClick()
        composeRule.onNodeWithText("Readiness checks").assertExists()
        schedulesNavigation.assertIsSelected()

        composeRule.onNodeWithContentDescription("Back").performClick()
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
        composeRule.onNodeWithText("Install camera profile").performClick()
        composeRule.runOnIdle { assertEquals(1, installRequests) }
        composeRule.onNode(hasText("Test recording") and hasClickAction()).assertIsNotEnabled()
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
        composeRule.onNodeWithText("Camera profile could not be installed").assertExists()
        composeRule.onNodeWithText("The environment does not match.").assertExists()
        composeRule.onNode(hasText("Test recording") and hasClickAction()).assertIsNotEnabled()
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
                        environment = "Android 17 · Pixel Camera version 69481630 · English (United States)",
                        compatibility = "Needs test",
                        verifiedForScheduling = false,
                    ),
                ),
                actions = UiActionAvailability(canRunRehearsal = true),
            ),
            onRunRehearsal = { rehearsalRequests += 1 },
        )

        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNode(hasText("Test recording") and hasClickAction()).performClick()
        composeRule.runOnIdle { assertEquals(1, rehearsalRequests) }
    }

    @Test
    fun profilesRouteShowsRestoredRehearsalDeadlineAndDisablesTest() {
        val detail = "Session session-rehearsal-active · STOP deadline 2026-08-10T06:30:00+04:00[Asia/Tbilisi]"
        setContent(
            state = LenswakeUiState(
                activeSession = ActiveSessionUiState(
                    sessionId = "session-rehearsal-active",
                    kind = ActiveSessionKind.REHEARSAL,
                    stopDeadline = Instant.parse("2026-08-10T02:30:00Z"),
                    title = "Active test recording",
                    detail = detail,
                    status = "STOP pending",
                ),
                actions = UiActionAvailability(
                    canRunRehearsal = false,
                    rehearsalUnavailableReason = "A test recording is active.",
                ),
            ),
        )

        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("Active test recording").assertExists()
        composeRule.onNodeWithText(detail).assertExists()
        composeRule.onNode(hasText("Test recording") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun schedulesRouteShowsRestoredScheduledSessionDeadline() {
        val detail = "Session session-scheduled-active · STOP deadline 2026-08-10T08:00:00+04:00[Asia/Tbilisi]"
        setContent(
            state = LenswakeUiState(
                activeSession = ActiveSessionUiState(
                    sessionId = "session-scheduled-active",
                    kind = ActiveSessionKind.SCHEDULED,
                    stopDeadline = Instant.parse("2026-08-10T04:00:00Z"),
                    title = "Active recording: Dawn",
                    detail = detail,
                    status = "Recording expected",
                ),
            ),
        )

        composeRule.onNodeWithText("Active recording: Dawn").assertExists()
        composeRule.onNodeWithText(detail).assertExists()
        composeRule.onNodeWithText("Recording expected").assertExists()
    }

    @Test
    fun profileBusyActionsShowProgressWithoutUnavailableCopy() {
        setContent(
            state = LenswakeUiState(
                profileInstall = ProfileInstallUiState.Installing,
                rehearsal = RehearsalActionUiState.Running,
                actions = UiActionAvailability(
                    installCandidateProfileUnavailableReason =
                        "Camera profile installation is in progress.",
                    rehearsalUnavailableReason = "A test recording is already running.",
                ),
            ),
        )
        composeRule.onNodeWithText("Profiles").performClick()

        listOf("Installing profile", "Testing camera").forEach { label ->
            val busyDescription = SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                label,
            )
            composeRule.onNode(hasText(label) and hasClickAction() and busyDescription)
                .performScrollTo()
                .assertIsNotEnabled()
        }
        composeRule.onNodeWithText("Camera profile installation is in progress.").assertDoesNotExist()
        composeRule.onNodeWithText("A test recording is already running.").assertDoesNotExist()
    }

    @Test
    fun primaryRoutesUseActionFocusedCopy() {
        setContent()

        composeRule.onNodeWithText("Profiles").performClick()
        composeRule.onNodeWithText("Camera profile").assertExists()
        composeRule.onNode(hasText("Test recording") and hasClickAction()).assertExists()
        listOf(
            "candidate profile",
            "production-path",
            "rehearsal",
            "selector",
            "persisting",
        ).forEach { technicalTerm ->
            composeRule.onNodeWithText(technicalTerm, substring = true, ignoreCase = true).assertDoesNotExist()
        }

        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(9)
        composeRule.onNodeWithText("No activity yet").assertExists()
        composeRule.onNodeWithText("persisted", substring = true, ignoreCase = true).assertDoesNotExist()
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

        composeRule.onNodeWithContentDescription("Activate schedule").performScrollTo().assertExists()
        composeRule.onNodeWithText("Save schedule").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, submitRequests) }
        composeRule.onNodeWithText("120×", substring = true).assertExists()
    }

    @Test
    fun scheduleSaveShowsProgressInTheSubmitButton() {
        val busyMessage = "Saving schedule…"
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
                        name = "Dawn",
                        startLocal = LocalDateTime.of(2030, 1, 1, 6, 0),
                        stopLocal = LocalDateTime.of(2030, 1, 1, 8, 0),
                        zoneId = ZoneId.of("Asia/Tbilisi"),
                        profileId = "profile-verified",
                    ),
                ),
                scheduleAction = ScheduleActionUiState.Working(busyMessage),
            ),
        )
        val busyDescription = SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            busyMessage,
        )

        composeRule.onNode(hasText(busyMessage) and hasClickAction() and busyDescription)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNode(hasText("Save schedule") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun scheduleProfileSelectorUsesSingleChoiceRadioOptions() {
        val profiles = listOf(
            ProfileSummaryUiState(
                id = "profile-8",
                title = "Pixel 8 Pro",
                environment = "Android 17 · Pixel Camera version 700000 · English",
                compatibility = "Verified for scheduling",
                verifiedForScheduling = true,
            ),
            ProfileSummaryUiState(
                id = "profile-9",
                title = "Pixel 9 Pro",
                environment = "Android 17 · Pixel Camera version 710000 · English",
                compatibility = "Verified for scheduling",
                verifiedForScheduling = true,
            ),
        )
        var updatedForm: ScheduleFormUiState? = null
        setContent(
            state = LenswakeUiState(
                profiles = profiles,
                scheduleEditor = ScheduleEditorUiState.Open(
                    mode = ScheduleEditorMode.Create,
                    form = ScheduleFormUiState(
                        name = "Dawn",
                        startLocal = LocalDateTime.of(2030, 1, 1, 6, 0),
                        stopLocal = LocalDateTime.of(2030, 1, 1, 8, 0),
                        zoneId = ZoneId.of("Asia/Tbilisi"),
                        profileId = "profile-8",
                    ),
                ),
            ),
            onUpdateScheduleForm = { updatedForm = it },
        )
        val radioButtonRole = SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.RadioButton,
        )

        composeRule.onNode(hasText("Pixel 8 Pro") and radioButtonRole)
            .performScrollTo()
            .assertIsSelected()
        composeRule.onNodeWithText("Pixel Camera version 710000", substring = true).assertExists()
        composeRule.onNode(hasText("Pixel 9 Pro") and radioButtonRole).performClick()

        composeRule.runOnIdle { assertEquals("profile-9", updatedForm?.profileId) }
    }

    @Test
    fun scheduleDeleteUsesModalConfirmation() {
        val schedule = ScheduleSummaryUiState(
            id = "schedule-1",
            title = "Dawn",
            timing = "Jan 1, 2030 06:00 - Jan 1, 2030 08:00",
            status = "Enabled",
            startLocal = LocalDateTime.of(2030, 1, 1, 6, 0),
            stopLocal = LocalDateTime.of(2030, 1, 1, 8, 0),
            zoneId = ZoneId.of("Asia/Tbilisi"),
            profileId = "profile-verified",
            enabled = true,
        )
        val state = androidx.compose.runtime.mutableStateOf(
            LenswakeUiState(schedules = listOf(schedule)),
        )
        var cancelled = false
        var confirmedScheduleId: String? = null
        composeRule.setContent {
            LenswakeTheme {
                LenswakeApp(
                    state = state.value,
                    onRequestDeleteSchedule = { scheduleId ->
                        state.value = state.value.copy(pendingDeleteScheduleId = scheduleId)
                    },
                    onCancelDeleteSchedule = {
                        cancelled = true
                        state.value = state.value.copy(pendingDeleteScheduleId = null)
                    },
                    onConfirmDeleteSchedule = { scheduleId ->
                        confirmedScheduleId = scheduleId
                        state.value = state.value.copy(pendingDeleteScheduleId = null)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Delete schedule").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete Dawn?").assertExists()
        composeRule.onNodeWithText("This can’t be undone.", substring = true).assertExists()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(true, cancelled) }
        composeRule.onNodeWithText("Delete Dawn?").assertDoesNotExist()

        composeRule.onNodeWithText("Delete schedule").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertEquals("schedule-1", confirmedScheduleId) }
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
