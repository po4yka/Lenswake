package dev.po4yka.lenswake.ui

import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfile
import dev.po4yka.lenswake.application.KnownPixelCameraProfileCatalog
import dev.po4yka.lenswake.application.RehearsalCoordinator
import dev.po4yka.lenswake.application.RehearsalResult
import dev.po4yka.lenswake.application.RehearsalResultCode
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.application.ScheduleWorkflow
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationAction
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
import dev.po4yka.lenswake.core.definitionFingerprint
import dev.po4yka.lenswake.core.LenswakeClock
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
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

@OptIn(ExperimentalCoroutinesApi::class)
abstract class LenswakeViewModelTestSupport {
    @BeforeEach
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    protected class FakeScheduleRepository : ScheduleRepository {
        private val schedules = MutableStateFlow<List<RecordingSchedule>>(emptyList())

        override fun observeSchedules(): Flow<List<RecordingSchedule>> = schedules
        override suspend fun get(id: ScheduleId): RecordingSchedule? =
            schedules.value.firstOrNull { it.id == id }

        override suspend fun save(schedule: RecordingSchedule) {
            schedules.value = schedules.value.filterNot { it.id == schedule.id } + schedule
        }

        override suspend fun delete(id: ScheduleId) {
            schedules.value = schedules.value.filterNot { it.id == id }
        }
    }

    protected class FakeProfileRepository : AutomationProfileRepository {
        private val profiles = MutableStateFlow<List<PixelCameraProfile>>(emptyList())
        private val issues = MutableStateFlow<List<ProfilePersistenceIssue>>(emptyList())

        override fun observeProfiles(): Flow<List<PixelCameraProfile>> = profiles
        override fun observePersistenceIssues(): Flow<List<ProfilePersistenceIssue>> = issues

        fun reportIssue(issue: ProfilePersistenceIssue) {
            issues.value = listOf(issue)
        }

        override suspend fun get(id: ProfileId): PixelCameraProfile? =
            profiles.value.firstOrNull { it.id == id }

        override suspend fun save(profile: PixelCameraProfile) {
            profiles.value = profiles.value.filterNot { it.id == profile.id } + profile
        }

        override suspend fun delete(id: ProfileId) {
            profiles.value = profiles.value.filterNot { it.id == id }
        }
    }

    protected class FakeExecutionRepository(
        initial: List<ExecutionSession> = emptyList(),
    ) : ExecutionRepository {
        private val executions = MutableStateFlow(initial)
        private val events = mutableMapOf<SessionId, MutableStateFlow<List<AutomationEvent>>>()

        override fun observeExecutions(): Flow<List<ExecutionSession>> = executions
        override fun observeExecution(id: SessionId): Flow<ExecutionSession?> =
            executions.map { sessions -> sessions.firstOrNull { it.id == id } }

        override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> =
            events.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

        override suspend fun get(id: SessionId): ExecutionSession? =
            executions.value.firstOrNull { it.id == id }

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

        fun persist(session: ExecutionSession) {
            executions.value = executions.value.filterNot { it.id == session.id } + session
        }
    }

    protected class FakeRecordingScheduler : RecordingScheduler {
        val events = mutableListOf<String>()

        override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> {
            events += "start"
            return Result.success(Unit)
        }

        override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> {
            events += "stop"
            return Result.success(Unit)
        }

        override suspend fun stageStart(schedule: RecordingSchedule): Result<Unit> = scheduleStart(schedule)
        override suspend fun stageStop(schedule: RecordingSchedule): Result<Unit> = scheduleStop(schedule)
        override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = Result.success(Unit)
        override suspend fun restoreAll(): Result<Unit> = Result.success(Unit)
    }

    protected class MutablePreflightProbe(
        var report: PreflightReport,
    ) : RuntimePreflightProbe {
        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val invalidations: Flow<Unit> = changes

        override suspend fun inspect(profiles: List<PixelCameraProfile>): PreflightReport = report

        fun invalidate() {
            check(changes.tryEmit(Unit)) { "Preflight invalidation was not delivered" }
        }
    }

    protected val now: Instant = Instant.parse("2026-08-09T05:30:00Z")
    protected val scheduleId = ScheduleId("schedule-1")
    protected val profileId = ProfileId("profile-1")
    protected val sessionId = SessionId("session-1")

    protected fun schedule() = RecordingSchedule(
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

    protected fun profile(): PixelCameraProfile {
        val selector = UiSelectorSet(
            selectors = listOf(UiSelector("com.google.android.GoogleCamera", text = "verified")),
            minimumScore = 10,
        )
        return PixelCameraProfile(
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
            targets = setOf(
                AutomationAction.SELECT_VIDEO,
                AutomationAction.SELECT_TIME_LAPSE,
                AutomationAction.OPEN_TIME_LAPSE_SPEED_CONTROL,
                AutomationAction.SELECT_REAR_MAIN_LENS,
                AutomationAction.SELECT_FRONT_LENS,
                AutomationAction.START_RECORDING,
                AutomationAction.STOP_RECORDING,
                AutomationAction.START_VIDEO_RECORDING,
                AutomationAction.STOP_VIDEO_RECORDING,
            ).associateWith { selector },
            speedTargets = mapOf(TimeLapseSpeed.X120 to selector),
            stateSignals = setOf(
                PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
                PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_X120_ACTIVE,
                PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN,
                PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE,
                PixelCameraStateSignal.FRONT_LENS_ACTIVE,
                PixelCameraStateSignal.RECORDING_ACTIVE,
                PixelCameraStateSignal.NOT_RECORDING,
            ).associateWith { selector },
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = now.minusSeconds(600),
        )
    }

    protected fun verifiedRehearsal(capture: CaptureConfiguration): ExecutionSession {
        val verifiedProfile = profile()
        val proofAt = checkNotNull(verifiedProfile.verifiedAt)
        return ExecutionSession(
            id = SessionId("rehearsal-${capture.mode.name}-${capture.lens.name}"),
            executionKey = "rehearsal/proof/${verifiedProfile.definitionFingerprint()}",
            kind = SessionKind.REHEARSAL,
            scheduleId = null,
            scheduleName = "Rehearsal",
            profileId = verifiedProfile.id,
            capture = capture,
            expectedStartAt = proofAt.minusSeconds(10),
            expectedStopAt = proofAt,
            status = SessionStatus.COMPLETED,
            recordActionAt = proofAt.minusSeconds(9),
            recordingVerifiedAt = proofAt.minusSeconds(8),
            stopActionAt = proofAt.minusSeconds(1),
            stoppedVerifiedAt = proofAt,
            mediaSavedVerifiedAt = proofAt,
            rehearsalVerifiedAt = proofAt,
            createdAt = proofAt.minusSeconds(10),
            updatedAt = proofAt,
        )
    }

    protected fun session() = ExecutionSession(
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

    protected fun activeRehearsalSession() = ExecutionSession(
        id = SessionId("session-rehearsal-active"),
        executionKey = "rehearsal:session-rehearsal-active",
        kind = SessionKind.REHEARSAL,
        scheduleId = null,
        scheduleName = null,
        profileId = profileId,
        capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
        expectedStartAt = now,
        expectedStopAt = now.plusSeconds(10),
        status = SessionStatus.RECORDING,
        recordActionAt = now.plusSeconds(1),
        recordingVerifiedAt = now.plusSeconds(2),
        createdAt = now,
        updatedAt = now.plusSeconds(2),
    )

    protected fun event() = AutomationEvent(
        id = EventId("event-1"),
        sessionId = sessionId,
        name = "automation.record.start_verified",
        sequence = 1,
        timestamp = now.plusSeconds(12),
        state = AutomationStateName.VERIFYING_RECORDING,
        operation = AutomationOperation.VERIFY_RECORDING,
        outcome = AutomationOutcome.SUCCEEDED,
    )

    protected fun blockedPreflight(
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

    protected fun rehearsalEligiblePreflight() = PreflightReport(
        checks = listOf(
            PreflightCheckType.EXACT_ALARMS,
            PreflightCheckType.NOTIFICATIONS,
            PreflightCheckType.MEDIA_VIDEO_ACCESS,
            PreflightCheckType.FULL_SCREEN_INTENT,
            PreflightCheckType.PIXEL_CAMERA_INSTALLED,
            PreflightCheckType.SECURE_CAMERA_RESOLVES,
            PreflightCheckType.ACCESSIBILITY_ENABLED,
            PreflightCheckType.ACCESSIBILITY_CONNECTED,
            PreflightCheckType.PROFILE_AVAILABLE,
        ).map { passedCheck(it) } + listOf(
            failedCheck(PreflightCheckType.DEVICE_WAKE, "Device wake is unavailable."),
            failedCheck(PreflightCheckType.PROFILE_COMPATIBILITY, "Profile needs rehearsal."),
            failedCheck(PreflightCheckType.REHEARSAL_CURRENT, "Rehearsal is not current."),
        ),
    )

    protected fun scheduleEligiblePreflight() = PreflightReport(
        checks = listOf(
            PreflightCheckType.EXACT_ALARMS,
            PreflightCheckType.NOTIFICATIONS,
            PreflightCheckType.MEDIA_VIDEO_ACCESS,
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
        ).map { passedCheck(it) },
    )

    protected fun installUseCase(repository: AutomationProfileRepository) = InstallKnownPixelCameraProfile(
        environmentProbe = {
            PortResult.Observed(
                KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630.environment,
            )
        },
        profileRepository = repository,
    )

    protected fun unavailableRehearsalCoordinator() = RehearsalCoordinator {
        RehearsalResult.Rejected(
            code = RehearsalResultCode.START_FAILED,
            message = "Not used by this test",
        )
    }

    protected fun scheduleWorkflow(
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

    private fun passedCheck(type: PreflightCheckType) = PreflightCheck(
        type = type,
        severity = PreflightSeverity.BLOCKING,
        status = PreflightStatus.PASSED,
        message = "$type passed.",
    )

    private fun failedCheck(type: PreflightCheckType, message: String) = PreflightCheck(
        type = type,
        severity = PreflightSeverity.BLOCKING,
        status = PreflightStatus.FAILED,
        message = message,
    )
}
