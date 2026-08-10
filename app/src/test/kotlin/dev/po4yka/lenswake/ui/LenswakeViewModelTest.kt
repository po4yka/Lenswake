package dev.po4yka.lenswake.ui

import androidx.lifecycle.viewModelScope
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
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
import dev.po4yka.lenswake.automation.PortResult
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
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
class LenswakeViewModelTest {
    @BeforeEach
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun mapperProjectsPersistedDataWithoutClaimingCurrentReadiness() {
        val state = LenswakeUiStateMapper.map(
            schedules = listOf(schedule()),
            profiles = listOf(profile()),
            events = listOf(event()),
            preflight = blockedPreflight(),
        )

        assertInstanceOf(ReadinessUiState.Blocked::class.java, state.readiness)
        assertEquals("Morning capture", state.schedules.single().title)
        assertEquals("Enabled", state.schedules.single().status)
        assertEquals(
            "Persisted as verified; see current compatibility in Setup",
            state.profiles.single().compatibility,
        )
        assertEquals(
            CapabilityStatus.BLOCKED,
            state.capabilities.single { it.name == "Lenswake Accessibility Service" }.status,
        )
        assertEquals("automation.record.start_verified", state.diagnosticEvents.single().title)
        assertFalse(state.actions.canCreateSchedule)
        assertTrue(state.actions.canInstallCandidateProfile)
        assertFalse(state.actions.canExportDiagnostics)
        assertEquals("Diagnostic export is not implemented yet.", state.actions.exportDiagnosticsUnavailableReason)
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
        )

        val incident = state.alarmTransportIncidents.single()
        assertEquals("stop-incident", incident.id)
        assertEquals(AlarmTransportIncidentUiAction.OPEN_PIXEL_CAMERA, incident.action)
        assertEquals("Scheduled STOP needs manual action", incident.title)
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
        )

        try {
            val loaded = async {
                viewModel.state.first {
                    it.schedules.size == 1 && it.profiles.size == 1 && it.diagnosticEvents.size == 1
                }
            }

            schedules.save(schedule())
            profiles.save(profile())
            executions.reservePixelCamera(session())
            executions.publish(event())

            val state = loaded.await()
            assertEquals("Morning capture", state.schedules.single().title)
            assertEquals("Pixel 8 Pro", state.profiles.single().title)
            assertEquals("VERIFY_RECORDING", state.diagnosticEvents.single().detail.substringAfterLast(" - "))
            assertInstanceOf(ReadinessUiState.Blocked::class.java, state.readiness)
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
        assertEquals("Needs rehearsal", installed.profiles.single().compatibility)
        assertFalse(installed.actions.canInstallCandidateProfile)
        assertEquals(
            "Candidate profile installed. A production rehearsal is still required.",
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
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        viewModel.installCandidateProfile()

        val failed = viewModel.state.first { it.profileInstall is ProfileInstallUiState.Failed }
        assertEquals(true, failed.actions.canInstallCandidateProfile)
        assertEquals(
            "No candidate profile matches Pixel 8 Pro, Android SDK 37, Pixel Camera ${Long.MAX_VALUE}, and ${unsupported.localeTag}.",
            (failed.profileInstall as ProfileInstallUiState.Failed).message,
        )
    }

    @Test
    fun runRehearsalUsesBoundedProductionRequestAndSurfacesPendingSafetyStop() = runTest {
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        val schedules = FakeScheduleRepository()
        var received: RehearsalRequest? = null
        val coordinator = RehearsalCoordinator { request ->
            received = request
            RehearsalResult.SafetyStopPending(
                sessionId = sessionId,
                message = "STOP remains unverified.",
            )
        }
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { rehearsalEligiblePreflight() },
            installUseCase(profiles),
            coordinator,
            scheduleWorkflow(schedules, profiles),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect()
        }

        assertEquals(true, viewModel.state.first { it.actions.canRunRehearsal }.actions.canRunRehearsal)
        viewModel.runRehearsal()

        val pending = viewModel.state.first { it.rehearsal is RehearsalActionUiState.SafetyStopPending }
        assertEquals(profileId, received?.profileId)
        assertEquals(Duration.ofSeconds(10), received?.recordingDuration)
        assertEquals(TimeLapseSpeed.X120, received?.capture?.speed)
        assertEquals(dev.po4yka.lenswake.core.LensSelection.REAR_MAIN, received?.capture?.lens)
        assertFalse(pending.actions.canRunRehearsal)
        assertEquals(
            "STOP is not yet verified; wait for the independent safety alarm before retrying.",
            pending.actions.rehearsalUnavailableReason,
        )
    }

    @Test
    fun createScheduleFormPersistsAndArmsBothAlarmsBeforeReportingSuccess() = runTest {
        val schedules = FakeScheduleRepository()
        val profiles = FakeProfileRepository().also { it.save(profile()) }
        val scheduler = FakeRecordingScheduler()
        val viewModel = LenswakeViewModel(
            schedules,
            profiles,
            FakeExecutionRepository(),
            RuntimePreflightProbe { scheduleEligiblePreflight() },
            installUseCase(profiles),
            unavailableRehearsalCoordinator(),
            ScheduleWorkflow(
                scheduleRepository = schedules,
                executionRepository = FakeExecutionRepository(),
                profileRepository = profiles,
                scheduler = scheduler,
                clock = LenswakeClock { now.minusSeconds(60) },
                preflightProbe = RuntimePreflightProbe { scheduleEligiblePreflight() },
            ),
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
        viewModel.updateScheduleForm(
            editor.form.copy(
                name = "Dawn",
                startLocal = "2026-08-09T10:30",
                stopLocal = "2026-08-09T11:30",
                zoneId = "Asia/Tbilisi",
            ),
        )
        viewModel.submitSchedule()

        val succeeded = withTimeout(Duration.ofSeconds(2).toMillis()) {
            viewModel.state.first {
                it.scheduleAction is ScheduleActionUiState.Succeeded && it.schedules.size == 1
            }
        }
        assertEquals(listOf("stop", "start"), scheduler.events)
        assertEquals("Dawn", succeeded.schedules.single().title)
        assertEquals("Enabled", succeeded.schedules.single().status)
        assertInstanceOf(ScheduleEditorUiState.Closed::class.java, succeeded.scheduleEditor)
    }

    private class FakeScheduleRepository : ScheduleRepository {
        private val schedules = MutableStateFlow<List<RecordingSchedule>>(emptyList())

        override fun observeSchedules(): Flow<List<RecordingSchedule>> = schedules
        override suspend fun get(id: ScheduleId): RecordingSchedule? = schedules.value.firstOrNull { it.id == id }
        override suspend fun save(schedule: RecordingSchedule) {
            schedules.value = schedules.value.filterNot { it.id == schedule.id } + schedule
        }

        override suspend fun delete(id: ScheduleId) {
            schedules.value = schedules.value.filterNot { it.id == id }
        }
    }

    private class FakeProfileRepository : AutomationProfileRepository {
        private val profiles = MutableStateFlow<List<PixelCameraProfile>>(emptyList())

        override fun observeProfiles(): Flow<List<PixelCameraProfile>> = profiles
        override suspend fun get(id: ProfileId): PixelCameraProfile? = profiles.value.firstOrNull { it.id == id }
        override suspend fun save(profile: PixelCameraProfile) {
            profiles.value = profiles.value.filterNot { it.id == profile.id } + profile
        }

        override suspend fun delete(id: ProfileId) {
            profiles.value = profiles.value.filterNot { it.id == id }
        }
    }

    private class FakeExecutionRepository : ExecutionRepository {
        private val executions = MutableStateFlow<List<ExecutionSession>>(emptyList())
        private val events = mutableMapOf<SessionId, MutableStateFlow<List<AutomationEvent>>>()

        override fun observeExecutions(): Flow<List<ExecutionSession>> = executions
        override fun observeExecution(id: SessionId): Flow<ExecutionSession?> =
            executions.mapValue { sessions -> sessions.firstOrNull { it.id == id } }

        override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> =
            events.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

        override suspend fun get(id: SessionId): ExecutionSession? = executions.value.firstOrNull { it.id == id }
        override suspend fun findPixelCameraOwnerForSchedule(scheduleId: ScheduleId): ExecutionSession? =
            executions.value.firstOrNull { it.scheduleId == scheduleId && it.ownsPixelCamera }

        override suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult {
            executions.value = executions.value.filterNot { it.id == session.id } + session
            return ExecutionReservationResult.Reserved(session, newlyCreated = true)
        }

        override suspend fun apply(
            change: ExecutionChange,
            event: AutomationEvent,
        ): ExecutionApplyResult = error("Not used by this projection test")

        fun publish(event: AutomationEvent) {
            val stream = events.getOrPut(event.sessionId) { MutableStateFlow(emptyList()) }
            stream.value = stream.value + event
        }
    }

    private class FakeRecordingScheduler : RecordingScheduler {
        val events = mutableListOf<String>()

        override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> {
            events += "start"
            return Result.success(Unit)
        }

        override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> {
            events += "stop"
            return Result.success(Unit)
        }
        override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = Result.success(Unit)
        override suspend fun restoreAll(): Result<Unit> = Result.success(Unit)
    }

    private class MutablePreflightProbe(
        var report: PreflightReport,
    ) : RuntimePreflightProbe {
        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val invalidations: Flow<Unit> = changes

        override suspend fun inspect(profiles: List<PixelCameraProfile>): PreflightReport = report

        fun invalidate() {
            check(changes.tryEmit(Unit)) { "Preflight invalidation was not delivered" }
        }
    }

    private companion object {
        val now: Instant = Instant.parse("2026-08-09T05:30:00Z")
        val scheduleId = ScheduleId("schedule-1")
        val profileId = ProfileId("profile-1")
        val sessionId = SessionId("session-1")

        fun schedule() = RecordingSchedule(
            id = scheduleId,
            name = "Morning capture",
            startAt = now,
            stopAt = now.plusSeconds(7_200),
            zoneId = ZoneId.of("Asia/Tbilisi"),
            capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X30),
            profileId = profileId,
            enabled = true,
            createdAt = now.minusSeconds(3_600),
            updatedAt = now.minusSeconds(3_600),
        )

        fun profile() = PixelCameraProfile(
            id = profileId,
            environment = PixelCameraEnvironment(
                deviceManufacturer = "Pixel",
                deviceModel = "Pixel 8 Pro",
                androidSdk = 37,
                androidBuildFingerprint = "google/husky/test",
                cameraPackage = "com.google.android.GoogleCamera",
                cameraVersionCode = 700_000L,
                localeTag = "en-US",
                displayWidthPx = 1_344,
                displayHeightPx = 2_992,
                densityDpi = 480,
            ),
            selectorSchemaVersion = 1,
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = now.minusSeconds(600),
        )

        fun session() = ExecutionSession(
            id = sessionId,
            executionKey = "schedule-1:start:2026-08-09T05:30:00Z",
            kind = SessionKind.SCHEDULED,
            scheduleId = scheduleId,
            scheduleName = "Morning capture",
            profileId = profileId,
            capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X30),
            expectedStartAt = now,
            expectedStopAt = now.plusSeconds(7_200),
            status = SessionStatus.STARTING,
            createdAt = now,
            updatedAt = now,
        )

        fun event() = AutomationEvent(
            id = EventId("event-1"),
            sessionId = sessionId,
            name = "automation.record.start_verified",
            sequence = 1,
            timestamp = now.plusSeconds(12),
            state = AutomationStateName.VERIFYING_RECORDING,
            operation = AutomationOperation.VERIFY_RECORDING,
            outcome = AutomationOutcome.SUCCEEDED,
        )

        fun blockedPreflight(
            accessibilityStatus: PreflightStatus = PreflightStatus.FAILED,
        ) = PreflightReport(
            checks = listOf(
                PreflightCheck(
                    type = PreflightCheckType.EXACT_ALARMS,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.PASSED,
                    message = "Exact alarms are available.",
                ),
                PreflightCheck(
                    type = PreflightCheckType.ACCESSIBILITY_ENABLED,
                    severity = PreflightSeverity.BLOCKING,
                    status = accessibilityStatus,
                    message = "Accessibility is disabled.",
                ),
                PreflightCheck(
                    type = PreflightCheckType.PROFILE_COMPATIBILITY,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.FAILED,
                    message = "A current profile is required.",
                ),
            ),
        )

        fun rehearsalEligiblePreflight() = PreflightReport(
            checks = listOf(
                PreflightCheckType.EXACT_ALARMS,
                PreflightCheckType.NOTIFICATIONS,
                PreflightCheckType.FULL_SCREEN_INTENT,
                PreflightCheckType.PIXEL_CAMERA_INSTALLED,
                PreflightCheckType.SECURE_CAMERA_RESOLVES,
                PreflightCheckType.ACCESSIBILITY_ENABLED,
                PreflightCheckType.ACCESSIBILITY_CONNECTED,
                PreflightCheckType.PROFILE_AVAILABLE,
            ).map { type ->
                PreflightCheck(
                    type = type,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.PASSED,
                    message = "$type passed.",
                )
            } + listOf(
                PreflightCheck(
                    type = PreflightCheckType.DEVICE_WAKE,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.FAILED,
                    message = "Device wake is unavailable.",
                ),
                PreflightCheck(
                    type = PreflightCheckType.PROFILE_COMPATIBILITY,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.FAILED,
                    message = "Profile needs rehearsal.",
                ),
                PreflightCheck(
                    type = PreflightCheckType.REHEARSAL_CURRENT,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.FAILED,
                    message = "Rehearsal is not current.",
                ),
            ),
        )

        fun scheduleEligiblePreflight() = PreflightReport(
            checks = listOf(
                PreflightCheckType.EXACT_ALARMS,
                PreflightCheckType.NOTIFICATIONS,
                PreflightCheckType.FULL_SCREEN_INTENT,
                PreflightCheckType.PIXEL_CAMERA_INSTALLED,
                PreflightCheckType.SECURE_CAMERA_RESOLVES,
                PreflightCheckType.DEVICE_WAKE,
                PreflightCheckType.ACCESSIBILITY_ENABLED,
                PreflightCheckType.ACCESSIBILITY_CONNECTED,
                PreflightCheckType.PROFILE_AVAILABLE,
                PreflightCheckType.PROFILE_COMPATIBILITY,
                PreflightCheckType.REHEARSAL_CURRENT,
                PreflightCheckType.BATTERY,
                PreflightCheckType.CHARGING,
                PreflightCheckType.STORAGE,
            ).map { type ->
                PreflightCheck(
                    type = type,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.PASSED,
                    message = "$type passed.",
                )
            },
        )

        fun installUseCase(repository: AutomationProfileRepository) = InstallKnownPixelCameraProfile(
            environmentProbe = {
                PortResult.Observed(
                    KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630.environment,
                )
            },
            profileRepository = repository,
        )

        fun unavailableRehearsalCoordinator() = RehearsalCoordinator {
            RehearsalResult.Rejected(
                code = RehearsalResultCode.START_FAILED,
                message = "Not used by this test",
            )
        }

        fun scheduleWorkflow(
            schedules: ScheduleRepository,
            profiles: AutomationProfileRepository,
        ) = ScheduleWorkflow(
            scheduleRepository = schedules,
            executionRepository = FakeExecutionRepository(),
            profileRepository = profiles,
            scheduler = FakeRecordingScheduler(),
            clock = LenswakeClock { now.minusSeconds(60) },
            preflightProbe = RuntimePreflightProbe { scheduleEligiblePreflight() },
        )
    }
}

private fun <T, R> MutableStateFlow<T>.mapValue(transform: (T) -> R): Flow<R> =
    map(transform)
