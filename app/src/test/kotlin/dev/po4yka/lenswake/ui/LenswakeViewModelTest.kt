package dev.po4yka.lenswake.ui

import androidx.lifecycle.viewModelScope
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfilePersistenceIssue
import dev.po4yka.lenswake.core.ProfilePersistenceIssueCode
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.application.AlarmTransportIncident
import dev.po4yka.lenswake.application.AlarmTransportIncidentAction
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfile
import dev.po4yka.lenswake.application.KnownPixelCameraProfileCatalog
import dev.po4yka.lenswake.application.RehearsalCoordinator
import dev.po4yka.lenswake.application.RehearsalResult
import dev.po4yka.lenswake.application.RehearsalResultCode
import dev.po4yka.lenswake.application.ScheduleWorkflow
import dev.po4yka.lenswake.core.definitionFingerprint
import dev.po4yka.lenswake.automation.PortResult
import java.time.Instant
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LenswakeUiStateMapperTest : LenswakeViewModelTestSupport() {
    @Test
    fun mapperUsesSessionIdAsTheStableNewestSessionTieBreaker() {
        val laterId = session().copy(
            id = SessionId("session-z"),
            executionKey = "execution-z",
        )
        val earlierId = session().copy(
            id = SessionId("session-a"),
            executionKey = "execution-a",
        )

        val state = LenswakeUiStateMapper.map(
            schedules = emptyList(),
            profiles = emptyList(),
            events = emptyList(),
            executions = listOf(laterId, earlierId),
            preflight = blockedPreflight(),
            now = now,
            strings = TestUiStringProvider,
        )

        assertEquals(listOf("session-a", "session-z"), state.diagnosticSessions.map { it.id })
    }

    @Test
    fun mapperBuildsSessionTimelineWithDurationConfidenceAndReliabilityMetrics() {
        val completedSession = session().copy(
            status = dev.po4yka.lenswake.core.SessionStatus.COMPLETED,
            recordingVerifiedAt = now.plusSeconds(10),
            stoppedVerifiedAt = now.plusSeconds(70),
            updatedAt = now.plusSeconds(75),
        )
        val events = reliabilityEvents()

        val state = LenswakeUiStateMapper.map(
            schedules = emptyList(),
            profiles = emptyList(),
            events = events,
            executions = listOf(completedSession),
            preflight = blockedPreflight(),
            now = now.plusSeconds(75),
            strings = TestUiStringProvider,
        )

        val diagnostics = state.diagnosticSessions.single()
        assertEquals("Morning capture", diagnostics.title)
        assertEquals("COMPLETED", diagnostics.status)
        assertEquals("1m 0s", diagnostics.duration)
        assertEquals(1, diagnostics.metrics.retryCount)
        assertEquals(1, diagnostics.metrics.fallbackCount)
        assertEquals(1, diagnostics.metrics.privilegedFallbackCount)
        assertEquals(180, diagnostics.metrics.selectorConfidence?.score)
        assertEquals(160, diagnostics.metrics.selectorConfidence?.minimumScore)
        assertEquals(listOf("event-dispatched", "event-retry", "event-privileged"), diagnostics.timeline.map { it.id })
        assertEquals("2s", diagnostics.timeline.first().duration)
        assertEquals(null, diagnostics.timeline[1].duration)
        assertEquals(180, diagnostics.timeline.first().selectorConfidence?.score)
        assertEquals("Selector match: #0 (RESOURCE_ID)", diagnostics.timeline.first().selectorMatch)
    }

    private fun reliabilityEvents() = listOf(
        event().copy(
            id = EventId("event-dispatched"),
            sequence = 1,
            timestamp = now.plusSeconds(10),
            outcome = AutomationOutcome.DISPATCHED,
            interactionMethod = InteractionMethod.ACCESSIBILITY_PROFILE_GESTURE,
            attempt = 1,
            durationMs = 2_000,
            metadata = mapOf(
                "selectorScore" to "180",
                "selectorMinimumScore" to "160",
                "selectorIndex" to "0",
                "selectorSignals" to "RESOURCE_ID",
            ),
        ),
        event().copy(
            id = EventId("event-retry"),
            sequence = 2,
            timestamp = now.plusSeconds(12),
            outcome = AutomationOutcome.RETRYING,
            attempt = 2,
        ),
        event().copy(
            id = EventId("event-privileged"),
            sequence = 3,
            timestamp = now.plusSeconds(15),
            outcome = AutomationOutcome.DISPATCHED,
            interactionMethod = InteractionMethod.PRIVILEGED_INPUT,
            attempt = 2,
        ),
    )

    @Test
    fun mapperProjectsPersistedDataWithoutClaimingCurrentReadiness() {
        val state = LenswakeUiStateMapper.map(
            schedules = listOf(schedule()),
            profiles = listOf(profile()),
            events = listOf(event()),
            executions = listOf(session()),
            preflight = blockedPreflight(),
            now = now,
            strings = TestUiStringProvider,
        )

        assertInstanceOf(ReadinessUiState.Blocked::class.java, state.readiness)
        assertEquals("Morning capture", state.schedules.single().title)
        assertEquals("Enabled", state.schedules.single().status)
        assertEquals(
            "Verified for scheduling",
            state.profiles.single().compatibility,
        )
        assertEquals(
            CapabilityStatus.BLOCKED,
            state.capabilities.single { it.name == "Lenswake Accessibility Service" }.status,
        )
        assertEquals("automation.record.start_verified", state.diagnosticSessions.single().timeline.single().title)
        assertFalse(state.actions.canCreateSchedule)
        assertTrue(state.actions.canInstallCandidateProfile)
        assertTrue(state.actions.canExportDiagnostics)
        assertEquals("", state.actions.exportDiagnosticsUnavailableReason)
    }

    @Test
    fun mapperExposesEveryCaptureWithProofForTheCurrentProfileDefinition() {
        val promotedCapture = CaptureConfiguration.Video(
            dev.po4yka.lenswake.core.LensSelection.FRONT,
        )
        val secondCaptureProof = verifiedRehearsal(
            CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
        ).copy(mediaSavedVerifiedAt = now.minusSeconds(601))
        val wrongFingerprintProof = verifiedRehearsal(
            CaptureConfiguration.Video(dev.po4yka.lenswake.core.LensSelection.REAR_MAIN),
        ).copy(executionKey = "rehearsal/proof/${"0".repeat(64)}")

        val state = LenswakeUiStateMapper.map(
            schedules = emptyList(),
            profiles = listOf(profile()),
            events = emptyList(),
            executions = listOf(
                verifiedRehearsal(promotedCapture),
                secondCaptureProof,
                wrongFingerprintProof,
            ),
            preflight = scheduleEligiblePreflight(),
            now = now,
            strings = TestUiStringProvider,
        )

        assertEquals(
            setOf(promotedCapture, secondCaptureProof.capture),
            state.profiles.single().supportedCaptures,
        )
        assertTrue(state.profiles.single().verifiedForScheduling)
        assertTrue(state.actions.canCreateSchedule)
    }

    @Test
    fun mapperKeepsTypedSetupRemediationWithItsFailedCapability() {
        val state = LenswakeUiStateMapper.map(
            schedules = emptyList(),
            profiles = emptyList(),
            events = emptyList(),
            preflight = PreflightReport(
                listOf(
                    PreflightCheck(
                        type = PreflightCheckType.NOTIFICATIONS,
                        severity = PreflightSeverity.BLOCKING,
                        status = PreflightStatus.FAILED,
                        message = "Notifications unavailable.",
                        remediation = SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION,
                    ),
                ),
            ),
            now = now,
            strings = TestUiStringProvider,
        )

        assertEquals(
            SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION,
            state.capabilities.single().remediation,
        )
    }

    @Test
    fun mapperKeepsDurableAlarmIncidentAndItsTypedCameraAction() {
        val state = LenswakeUiStateMapper.map(
            schedules = emptyList(),
            profiles = emptyList(),
            events = emptyList(),
            incidents = listOf(
                AlarmTransportIncident(
                    id = "stop-incident",
                    code = dev.po4yka.lenswake.alarm.AlarmTransportFailureCode.STOP_TERMINAL_REJECTED,
                    title = "Scheduled STOP needs manual action",
                    detail = "Camera may still be recording.",
                    recordedAtEpochMillis = 1_000L,
                    action = AlarmTransportIncidentAction.OPEN_PIXEL_CAMERA,
                ),
            ),
            preflight = blockedPreflight(),
            now = now,
            strings = TestUiStringProvider,
        )

        val incident = state.alarmTransportIncidents.single()
        assertEquals("stop-incident", incident.id)
        assertEquals(AlarmTransportIncidentUiAction.OPEN_PIXEL_CAMERA, incident.action)
        assertEquals("Scheduled STOP needs manual action", incident.title)
    }

    @Test
    fun mapperKeepsCorruptProfileEntryVisibleInDiagnostics() {
        val state = LenswakeUiStateMapper.map(
            schedules = emptyList(),
            profiles = emptyList(),
            events = emptyList(),
            profileIssues = listOf(
                ProfilePersistenceIssue(
                    entryKey = "profile-corrupt",
                    code = ProfilePersistenceIssueCode.CORRUPT_ENTRY,
                ),
            ),
            preflight = blockedPreflight(),
            now = now,
            strings = TestUiStringProvider,
        )

        val issue = state.profilePersistenceIssues.single()
        assertEquals("profile-corrupt", issue.id)
        assertEquals("Camera profile storage issue", issue.title)
        assertTrue(issue.detail.contains("profile-corrupt"))
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
class LenswakeViewModelTest : LenswakeViewModelTestSupport() {
    @Test
    fun viewModelCollectsProfilePersistenceIssuesIntoDiagnosticsState() = runTest {
        val schedules = FakeScheduleRepository()
        val profiles = FakeProfileRepository()
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { blockedPreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )

        try {
            profiles.reportIssue(
                ProfilePersistenceIssue(
                    entryKey = "profile-corrupt",
                    code = ProfilePersistenceIssueCode.CORRUPT_ENTRY,
                ),
            )

            val state = viewModel.state.first { it.profilePersistenceIssues.isNotEmpty() }
            assertEquals("profile-corrupt", state.profilePersistenceIssues.single().id)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun defaultEmptyIncidentSourceEmitsAndDoesNotBlockInitialState() = runTest {
        val schedules = FakeScheduleRepository()
        val profiles = FakeProfileRepository()
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { blockedPreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )

        try {
            assertTrue(viewModel.state.first().alarmTransportIncidents.isEmpty())
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun viewModelObservesRepositoryChangesAndExecutionEvents() = runTest {
        val schedules = FakeScheduleRepository()
        val profiles = FakeProfileRepository()
        val executions = FakeExecutionRepository()
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            executions,
            RuntimePreflightProbe { blockedPreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )

        try {
            val loaded = async {
                viewModel.state.first {
                    it.schedules.size == 1 &&
                        it.profiles.size == 1 &&
                        it.diagnosticSessions.singleOrNull()?.timeline?.size == 1
                }
            }

            schedules.save(schedule())
            profiles.save(profile())
            executions.reservePixelCamera(session())
            executions.publish(event())

            val state = loaded.await()
            assertEquals("Morning capture", state.schedules.single().title)
            assertEquals("Pixel 8 Pro", state.profiles.single().title)
            assertEquals(
                "VERIFY_RECORDING",
                state.diagnosticSessions.single().timeline.single().detail.substringAfterLast(" - "),
            )
            assertInstanceOf(ReadinessUiState.Blocked::class.java, state.readiness)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun viewModelKeepsTheCompleteTimelineForAnObservedSession() = runTest {
        val schedules = FakeScheduleRepository()
        val profiles = FakeProfileRepository()
        val executions = FakeExecutionRepository()
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            executions,
            RuntimePreflightProbe { blockedPreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )

        try {
            executions.reservePixelCamera(session())
            repeat(75) { index ->
                executions.publish(
                    event().copy(
                        id = EventId("event-$index"),
                        sequence = index.toLong(),
                        timestamp = now.plusMillis(index.toLong()),
                    ),
                )
            }

            val state = viewModel.state.first {
                it.diagnosticSessions.singleOrNull()?.timeline?.size == 75
            }

            assertEquals(75, state.diagnosticSessions.single().timeline.size)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun refreshAndProbeInvalidationRecomputeRuntimeReadiness() = runTest {
        val probe = MutablePreflightProbe(blockedPreflight())
        val schedules = FakeScheduleRepository()
        val profiles = FakeProfileRepository()
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            probe,
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        val accessibilityName = "Lenswake Accessibility Service"
        viewModel.state.first {
            it.capabilities.any { capability ->
                capability.name == accessibilityName && capability.status == CapabilityStatus.BLOCKED
            }
        }

        probe.report = blockedPreflight(accessibilityStatus = PreflightStatus.PASSED)
        probe.invalidate()
        viewModel.state.first {
            it.capabilities.any { capability ->
                capability.name == accessibilityName && capability.status == CapabilityStatus.AVAILABLE
            }
        }

        probe.report = blockedPreflight(accessibilityStatus = PreflightStatus.FAILED)
        viewModel.refreshPreflight()
        viewModel.state.first {
            it.capabilities.any { capability ->
                capability.name == accessibilityName && capability.status == CapabilityStatus.BLOCKED
            }
        }
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
class LenswakeViewModelProfileActionsTest : LenswakeViewModelTestSupport() {
    @Test
    fun installCandidateProfilePersistsCatalogCandidateAndExposesRehearsalRequirement() = runTest {
        val profiles = FakeProfileRepository()
        val schedules = FakeScheduleRepository()
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { blockedPreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        assertEquals(ProfileInstallUiState.Idle, viewModel.state.first().profileInstall)
        assertEquals(true, viewModel.state.first().actions.canInstallCandidateProfile)

        viewModel.installCandidateProfile()

        val installed = viewModel.state.first {
            it.profileInstall is ProfileInstallUiState.Succeeded && it.profiles.size == 1
        }
        assertEquals("Needs test", installed.profiles.single().compatibility)
        assertFalse(installed.actions.canInstallCandidateProfile)
        assertEquals(
            "Camera profile installed. Test recording is required before scheduling.",
            (installed.profileInstall as ProfileInstallUiState.Succeeded).message,
        )
    }

    @Test
    fun installCandidateProfileKeepsFailureVisibleAndAllowsRetry() = runTest {
        val profiles = FakeProfileRepository()
        val schedules = FakeScheduleRepository()
        val unsupported = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630.environment.copy(
            cameraVersionCode = Long.MAX_VALUE,
        )
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { blockedPreflight() },
            InstallKnownPixelCameraProfile(
                environmentProbe = { PortResult.Observed(unsupported) },
                profileRepository = profiles,
            ),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        viewModel.installCandidateProfile()

        val failed = viewModel.state.first { it.profileInstall is ProfileInstallUiState.Failed }
        assertEquals(true, failed.actions.canInstallCandidateProfile)
        assertEquals(
            "No camera profile is available for Pixel 8 Pro with this Pixel Camera version and language.",
            (failed.profileInstall as ProfileInstallUiState.Failed).message,
        )
    }

    @Test
    fun profileBoundRehearsalUsesRequestedProfileAndSurfacesPendingSafetyStop() = runTest {
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        val schedules = FakeScheduleRepository()
        val executions = FakeExecutionRepository()
        val activeSession = activeRehearsalSession()
        var received: RehearsalRequest? = null
        val coordinator = RehearsalCoordinator { request ->
            received = request
            executions.persist(activeSession)
            RehearsalResult.SafetyStopPending(
                sessionId = activeSession.id,
                message = "STOP remains unverified.",
            )
        }
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            executions,
            RuntimePreflightProbe { rehearsalEligiblePreflight() },
            installUseCase(profiles),
            coordinator,
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        assertEquals(true, viewModel.state.first { it.actions.canRunRehearsal }.actions.canRunRehearsal)
        viewModel.runProfileRehearsal(profileId.value)

        val pending = viewModel.state.first { it.rehearsal is RehearsalActionUiState.SafetyStopPending }
        assertPendingRehearsal(received, pending, activeSession)

        val replacementSession = activeSession.copy(
            id = SessionId("session-rehearsal-replacement"),
            executionKey = "rehearsal:session-rehearsal-replacement",
            expectedStopAt = activeSession.expectedStopAt.plusSeconds(30),
        )
        executions.persist(replacementSession)
        executions.complete(activeSession)

        val replacement = viewModel.state.first {
            it.activeSession?.sessionId == replacementSession.id.value &&
                it.rehearsal == RehearsalActionUiState.Idle
        }
        assertFalse(replacement.actions.canRunRehearsal)
        executions.complete(replacementSession)
        val reconciled = viewModel.state.first { it.activeSession == null }
        assertTrue(reconciled.actions.canRunRehearsal)
    }

    @Test
    fun profileBoundRehearsalSelectsTheFirstCaptureWithoutCurrentProof() = runTest {
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        val proof = verifiedRehearsal(
            CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
        )
        val executions = FakeExecutionRepository(listOf(proof))
        var received: RehearsalRequest? = null
        val viewModel = LenswakeViewModel(
            FakeScheduleRepository(),
            profiles,
            executions,
            RuntimePreflightProbe { rehearsalEligiblePreflight() },
            installUseCase(profiles),
            RehearsalCoordinator { request ->
                received = request
                RehearsalResult.Rejected(RehearsalResultCode.START_FAILED, "Expected test stop")
            },
            scheduleWorkflow(FakeScheduleRepository(), profiles),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        viewModel.state.first { it.actions.canRunRehearsal }
        viewModel.runProfileRehearsal(profileId.value)
        viewModel.state.first { it.rehearsal is RehearsalActionUiState.Failed }

        assertEquals(
            CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120, dev.po4yka.lenswake.core.LensSelection.FRONT),
            received?.capture,
        )
    }

    @Test
    fun scheduleBoundRehearsalUsesPersistedScheduleProfileAndCapture() = runTest {
        val requestedSchedule = schedule()
        val schedules = FakeScheduleRepository().also { it.save(requestedSchedule) }
        val profiles = FakeProfileRepository().also {
            it.save(profile().copy(id = ProfileId("000-first-profile")))
            it.save(profile())
        }
        var received: RehearsalRequest? = null
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { rehearsalEligiblePreflight() },
            installUseCase(profiles),
            RehearsalCoordinator { request ->
                received = request
                RehearsalResult.Rejected(RehearsalResultCode.START_FAILED, "Expected test stop")
            },
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        viewModel.state.first { it.actions.canRunRehearsal }
        viewModel.runScheduleRehearsal(requestedSchedule.id.value)
        val failed = viewModel.state.first { it.rehearsal is RehearsalActionUiState.Failed }

        assertEquals(requestedSchedule.profileId, received?.profileId)
        assertEquals(requestedSchedule.capture, received?.capture)
        assertEquals(Duration.ofSeconds(10), received?.recordingDuration)
        assertEquals(requestedSchedule.id, received?.scheduleId)
        assertEquals(
            RehearsalTargetUiState.Schedule(requestedSchedule.id.value),
            failed.rehearsalTarget,
        )
    }

    @Test
    fun missingScheduleFailsWithoutStartingGlobalRehearsal() = runTest {
        val schedules = FakeScheduleRepository()
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        var coordinatorCalls = 0
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { rehearsalEligiblePreflight() },
            installUseCase(profiles),
            RehearsalCoordinator {
                coordinatorCalls += 1
                error("Missing schedule must not start rehearsal")
            },
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        viewModel.state.first { it.actions.canRunRehearsal }
        viewModel.runScheduleRehearsal("missing-schedule")
        val failed = viewModel.state.first { it.rehearsal is RehearsalActionUiState.Failed }

        assertEquals(0, coordinatorCalls)
        assertEquals(
            RehearsalTargetUiState.Schedule("missing-schedule"),
            failed.rehearsalTarget,
        )
    }

    @Test
    fun secondRehearsalNeverPairsItsTargetWithThePreviousOutcome() = runTest {
        val firstSchedule = schedule().copy(id = ScheduleId("schedule-first"))
        val secondSchedule = schedule().copy(id = ScheduleId("schedule-second"))
        val schedules = FakeScheduleRepository().also {
            it.save(firstSchedule)
            it.save(secondSchedule)
        }
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        var calls = 0
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { rehearsalEligiblePreflight() },
            installUseCase(profiles),
            RehearsalCoordinator {
                calls += 1
                val code = if (calls == 1) {
                    RehearsalResultCode.START_FAILED
                } else {
                    RehearsalResultCode.STOP_FAILED
                }
                RehearsalResult.Rejected(code, "Expected test outcome")
            },
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )
        val emitted = mutableListOf<LenswakeUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect(emitted::add)
        }

        viewModel.state.first { it.actions.canRunRehearsal }
        viewModel.runScheduleRehearsal(firstSchedule.id.value)
        val firstOutcome = viewModel.state.first {
            it.rehearsalTarget == RehearsalTargetUiState.Schedule(firstSchedule.id.value) &&
                it.rehearsal is RehearsalActionUiState.Failed
        }.rehearsal
        emitted.clear()

        viewModel.runScheduleRehearsal(secondSchedule.id.value)
        viewModel.state.first {
            it.rehearsalTarget == RehearsalTargetUiState.Schedule(secondSchedule.id.value) &&
                it.rehearsal is RehearsalActionUiState.Failed
        }

        assertTrue(
            emitted.any {
                it.rehearsalTarget == RehearsalTargetUiState.Schedule(secondSchedule.id.value) &&
                    it.rehearsal == RehearsalActionUiState.Running
            },
        )
        assertFalse(
            emitted.any {
                it.rehearsalTarget == RehearsalTargetUiState.Schedule(secondSchedule.id.value) &&
                    it.rehearsal == firstOutcome
            },
        )
    }

    @Test
    fun recreatedViewModelRestoresActiveSessionAndStopDeadline() = runTest {
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        val schedules = FakeScheduleRepository()
        val executions = FakeExecutionRepository()
        val activeSession = activeRehearsalSession()
        val laterSession = activeSession.copy(
            id = SessionId("session-rehearsal-later"),
            executionKey = "rehearsal:session-rehearsal-later",
            expectedStopAt = activeSession.expectedStopAt.plusSeconds(30),
            status = SessionStatus.FAILED,
        )
        executions.persist(laterSession)
        executions.persist(activeSession)
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            executions,
            RuntimePreflightProbe { rehearsalEligiblePreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
            clock = LenswakeClock { now.plusSeconds(20) },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        val restored = viewModel.state.first { it.activeSession != null }

        assertEquals(activeSession.id.value, restored.activeSession?.sessionId)
        assertEquals(activeSession.expectedStopAt, restored.activeSession?.stopDeadline)
        assertRestoredSessionDetail(restored, activeSession)
        assertEquals("STOP overdue", restored.activeSession?.status)
        assertFalse(restored.actions.canRunRehearsal)

        executions.complete(activeSession)

        val promoted = viewModel.state.first { it.activeSession?.sessionId == laterSession.id.value }
        assertEquals(laterSession.expectedStopAt, promoted.activeSession?.stopDeadline)
        assertEquals("STOP pending", promoted.activeSession?.status)
        executions.complete(laterSession)

        val cleared = viewModel.state.first { it.activeSession == null }
        assertTrue(cleared.actions.canRunRehearsal)

        val scheduledSession = session()
        executions.persist(scheduledSession)
        val scheduled = viewModel.state.first { it.activeSession?.sessionId == scheduledSession.id.value }
        assertEquals(ActiveSessionKind.SCHEDULED, scheduled.activeSession?.kind)
        assertEquals("Active recording: Morning capture", scheduled.activeSession?.title)
        assertEquals(scheduledSession.expectedStopAt, scheduled.activeSession?.stopDeadline)
    }

    @Test
    fun activeSessionBecomesOverdueWhenStopDeadlinePassesWithoutRepositoryUpdate() = runTest {
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        val schedules = FakeScheduleRepository()
        val executions = FakeExecutionRepository()
        val activeSession = activeRehearsalSession()
        var clockNow = now
        executions.persist(activeSession)
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            executions,
            RuntimePreflightProbe { rehearsalEligiblePreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
            clock = LenswakeClock { clockNow },
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        assertEquals(
            "Recording expected",
            viewModel.state.first { it.activeSession != null }.activeSession?.status,
        )

        clockNow = activeSession.expectedStopAt.minusNanos(1)
        testScheduler.advanceTimeBy(Duration.ofSeconds(10).toMillis())
        testScheduler.runCurrent()

        assertEquals("Recording expected", viewModel.state.value.activeSession?.status)

        clockNow = activeSession.expectedStopAt.plusNanos(1)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()

        assertEquals("STOP overdue", viewModel.state.value.activeSession?.status)
    }

    private fun assertPendingRehearsal(
        received: RehearsalRequest?,
        pending: LenswakeUiState,
        activeSession: ExecutionSession,
    ) {
        assertEquals(profileId, received?.profileId)
        assertEquals(Duration.ofSeconds(10), received?.recordingDuration)
        assertEquals(TimeLapseSpeed.X120, received?.capture?.timeLapseSpeed)
        assertEquals(dev.po4yka.lenswake.core.LensSelection.REAR_MAIN, received?.capture?.lens)
        assertEquals(RehearsalTargetUiState.Profile(profileId.value), pending.rehearsalTarget)
        assertFalse(pending.actions.canRunRehearsal)
        assertTrue(pending.actions.rehearsalUnavailableReason.contains(activeSession.id.value))
    }

    private fun assertRestoredSessionDetail(
        restored: LenswakeUiState,
        activeSession: ExecutionSession,
    ) {
        val deadline = activeSession.expectedStopAt.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
        assertTrue(restored.activeSession?.detail?.contains(activeSession.id.value) == true)
        assertTrue(restored.activeSession?.detail?.contains(deadline) == true)
    }

    private fun FakeExecutionRepository.complete(session: ExecutionSession) {
        persist(
            session.copy(
                status = SessionStatus.COMPLETED,
                stoppedVerifiedAt = session.expectedStopAt,
                revision = session.revision + 1,
                updatedAt = session.expectedStopAt,
            ),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LenswakeViewModelScheduleActionsTest : LenswakeViewModelTestSupport() {
    @Test
    fun confirmedDeleteClosesConfirmationAndRemovesSchedule() = runTest {
        val schedules = FakeScheduleRepository().also { it.save(schedule()) }
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { scheduleEligiblePreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            scheduleWorkflow(schedules, profiles),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        viewModel.state.first { it.schedules.singleOrNull()?.id == scheduleId.value }
        viewModel.requestDeleteSchedule(scheduleId.value)
        assertEquals(
            scheduleId.value,
            viewModel.state.first { it.pendingDeleteScheduleId != null }.pendingDeleteScheduleId,
        )

        viewModel.confirmDeleteSchedule(scheduleId.value)

        val deleted = viewModel.state.first {
            it.scheduleAction is ScheduleActionUiState.Succeeded && it.schedules.isEmpty()
        }
        assertEquals(null, deleted.pendingDeleteScheduleId)
    }

    @Test
    fun createScheduleFormPersistsAndArmsBothAlarmsBeforeReportingSuccess() = runTest {
        val schedules = FakeScheduleRepository()
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        val scheduler = FakeRecordingScheduler()
        val proof = verifiedRehearsal(CaptureConfiguration.Video(dev.po4yka.lenswake.core.LensSelection.FRONT))
        val executions = FakeExecutionRepository(listOf(proof))
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            executions,
            RuntimePreflightProbe { scheduleEligiblePreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            ScheduleWorkflow(
                scheduleRepository = schedules,
                executionRepository = executions,
                profileRepository = profiles,
                scheduler = scheduler,
                clock = LenswakeClock { now.minusSeconds(60) },
                preflightProbe = RuntimePreflightProbe { scheduleEligiblePreflight() },
            ),
            TestUiStringProvider,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        withTimeout(Duration.ofSeconds(2).toMillis()) {
            viewModel.state.first { it.actions.canCreateSchedule }
        }
        viewModel.beginCreateSchedule()
        val editor = withTimeout(Duration.ofSeconds(2).toMillis()) {
            viewModel.state.first { it.scheduleEditor is ScheduleEditorUiState.Open }
        }.scheduleEditor as ScheduleEditorUiState.Open
        assertDefaultScheduleForm(editor.form)
        viewModel.updateScheduleForm(
            editor.form.copy(
                name = "Dawn",
                startLocal = LocalDateTime.of(2026, 8, 9, 10, 30),
                stopLocal = LocalDateTime.of(2026, 8, 9, 11, 30),
                zoneId = ZoneId.of("Asia/Tbilisi"),
                captureMode = CaptureMode.VIDEO,
                timeLapseSpeed = TimeLapseSpeed.X5,
                lens = dev.po4yka.lenswake.core.LensSelection.FRONT,
            ),
        )
        viewModel.submitSchedule()

        val succeeded = withTimeout(Duration.ofSeconds(2).toMillis()) {
            viewModel.state.first {
                it.scheduleAction is ScheduleActionUiState.Succeeded && it.schedules.size == 1
            }
        }
        assertCreatedSchedule(succeeded, schedules, scheduler)
    }

    private suspend fun assertCreatedSchedule(
        succeeded: LenswakeUiState,
        schedules: ScheduleRepository,
        scheduler: FakeRecordingScheduler,
    ) {
        assertEquals(listOf("stop", "start"), scheduler.events)
        assertEquals("Dawn", succeeded.schedules.single().title)
        assertEquals("Enabled", succeeded.schedules.single().status)
        assertEquals(
            CaptureConfiguration.Video(dev.po4yka.lenswake.core.LensSelection.FRONT),
            schedules.get(ScheduleId(succeeded.schedules.single().id))?.capture,
        )
        assertInstanceOf(ScheduleEditorUiState.Closed::class.java, succeeded.scheduleEditor)
    }

    private fun assertDefaultScheduleForm(form: ScheduleFormUiState) {
        assertEquals("Time Lapse", form.name)
        assertEquals(
            Duration.ofHours(1),
            Duration.between(requireNotNull(form.startLocal), requireNotNull(form.stopLocal)),
        )
    }
}
