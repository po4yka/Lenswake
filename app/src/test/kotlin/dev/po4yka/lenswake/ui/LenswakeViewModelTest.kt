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
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        assertEquals("Enabled; alarm registration not verified", state.schedules.single().status)
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
        assertFalse(state.actions.canExportDiagnostics)
        assertEquals("Diagnostic export is not implemented yet.", state.actions.exportDiagnosticsUnavailableReason)
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
        )

        try {
            val loaded = async {
                viewModel.state.first {
                    it.schedules.size == 1 && it.profiles.size == 1 && it.diagnosticEvents.size == 1
                }
            }

            schedules.save(schedule())
            profiles.save(profile())
            executions.create(session())
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
        override suspend fun findActiveForSchedule(scheduleId: ScheduleId): ExecutionSession? =
            executions.value.firstOrNull { it.scheduleId == scheduleId }

        override suspend fun create(session: ExecutionSession) {
            executions.value = executions.value.filterNot { it.id == session.id } + session
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

        fun blockedPreflight() = PreflightReport(
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
                    status = PreflightStatus.FAILED,
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
    }
}

private fun <T, R> MutableStateFlow<T>.mapValue(transform: (T) -> R): Flow<R> =
    map(transform)
