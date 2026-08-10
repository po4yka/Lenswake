package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.alarm.AlarmHandlingResult
import dev.po4yka.lenswake.alarm.RehearsalStopBackstop
import dev.po4yka.lenswake.alarm.RehearsalStopTrigger
import dev.po4yka.lenswake.automation.AutomationEngine
import dev.po4yka.lenswake.automation.AutomationRunResult
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.EnvironmentCapabilityStatus
import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EnvironmentSnapshotCaptureResult
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.EnvironmentSnapshotRepository
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionReport
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultRehearsalCoordinatorTest {
    private val now = Instant.parse("2026-08-10T05:30:00Z")
    private val environment = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        androidSdk = 37,
        androidBuildFingerprint = "google/husky/test",
        cameraPackage = "com.google.android.GoogleCamera",
        cameraVersionCode = 6_948_163_000,
        localeTag = "en-US",
        displayWidthPx = 1344,
        displayHeightPx = 2992,
        densityDpi = 489,
    )
    private val profile = PixelCameraProfile(
        id = ProfileId("profile"),
        environment = environment,
        selectorSchemaVersion = 1,
        compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
        verifiedAt = null,
    )
    private val request = RehearsalRequest(
        profileId = profile.id,
        capture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X30,
            lens = LensSelection.REAR_MAIN,
        ),
        recordingDuration = Duration.ofSeconds(5),
    )

    @Test
    fun successfulRehearsalArmsBackstopBeforeStartAndPromotesExactProof() = runBlocking {
        val fixture = fixture()

        val result = fixture.coordinator.run(request)

        val completed = assertInstanceOf(RehearsalResult.Completed::class.java, result)
        assertEquals(now.plusSeconds(95), completed.session.expectedStopAt)
        assertEquals(completed.session.stoppedVerifiedAt, completed.verifiedProfile.verifiedAt)
        assertEquals(ProfileCompatibility.VERIFIED, fixture.profiles.saved.compatibility)
        assertEquals(listOf("schedule", "start", "delay", "stop", "cancel"), fixture.order)
    }

    @Test
    fun backstopFailureMarksSessionTerminalAndNeverStarts() = runBlocking {
        val fixture = fixture(backstopScheduleFails = true)

        val result = fixture.coordinator.run(request)

        val rejected = assertInstanceOf(RehearsalResult.Rejected::class.java, result)
        assertEquals(RehearsalResultCode.BACKSTOP_UNAVAILABLE, rejected.code)
        assertEquals(0, fixture.engine.startCalls)
        assertEquals(SessionStatus.FAILED, fixture.executions.sessions.values.single().status)
        assertEquals(1, fixture.backstop.cancelCalls)
    }

    @Test
    fun earlyAlarmStopIsTerminallyRejectedWithoutEngineMutation() = runBlocking {
        val fixture = fixture()
        val session = fixture.executions.seedRehearsal(now, request, profile.id)

        val result = fixture.alarmCoordinator.handle(
            RehearsalStopTrigger(session.id, session.expectedStopAt),
        )

        assertInstanceOf(AlarmHandlingResult.TerminalRejected::class.java, result)
        assertEquals(0, fixture.engine.stopCalls)
        assertEquals(null, fixture.executions.sessions.getValue(session.id).alarmStopDeliveredAt)
    }

    @Test
    fun missingStopProofCancelsBackstopWithoutPromotionAfterVerifiedStop() = runBlocking {
        val fixture = fixture(completeStopProof = false)

        val result = fixture.coordinator.run(request)

        assertInstanceOf(RehearsalResult.Rejected::class.java, result)
        assertEquals(ProfileCompatibility.NEEDS_REHEARSAL, fixture.profiles.saved.compatibility)
        assertEquals(1, fixture.backstop.cancelCalls)
    }

    @Test
    fun cancellationAfterVerifiedStartRunsSafetyStopBeforePropagation() {
        val fixture = fixture(delayFailure = CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking { fixture.coordinator.run(request) }
        }

        assertEquals(1, fixture.engine.stopCalls)
        assertTrue(fixture.backstop.cancelCalls >= 1)
        assertEquals(ProfileCompatibility.NEEDS_REHEARSAL, fixture.profiles.saved.compatibility)
    }

    @Test
    fun changedProfileDefinitionIsNotPromoted() = runBlocking {
        val fixture = fixture(replaceProfileDuringDelay = true)

        val result = fixture.coordinator.run(request)

        assertInstanceOf(RehearsalResult.Rejected::class.java, result)
        assertEquals(ProfileCompatibility.NEEDS_REHEARSAL, fixture.profiles.saved.compatibility)
        assertEquals(2, fixture.profiles.saved.selectorSchemaVersion)
        assertEquals(1, fixture.backstop.cancelCalls)
    }

    @Test
    fun transientSessionReadFailureKeepsAlarmRetryable() = runBlocking {
        val fixture = fixture(clockNow = now.plusSeconds(96))
        val session = fixture.executions.seedRehearsal(now, request, profile.id)
        fixture.executions.failGet = true

        val result = fixture.alarmCoordinator.handle(
            RehearsalStopTrigger(session.id, session.expectedStopAt),
        )

        assertInstanceOf(AlarmHandlingResult.Retryable::class.java, result)
        assertEquals(0, fixture.engine.stopCalls)
        assertEquals(0, fixture.backstop.cancelCalls)
    }

    @Test
    fun alarmWithoutRecordingOwnershipReleasesCameraForNextRehearsal() = runBlocking {
        val fixture = fixture(clockNow = now.plusSeconds(96))
        val owned = fixture.executions.seedRehearsal(now, request, profile.id)
        fixture.executions.sessions[owned.id] = owned.copy(
            status = SessionStatus.PENDING,
            recordActionAt = null,
            recordingVerifiedAt = null,
        )

        val result = fixture.alarmCoordinator.handle(
            RehearsalStopTrigger(owned.id, owned.expectedStopAt),
        )

        assertInstanceOf(AlarmHandlingResult.Accepted::class.java, result)
        assertEquals(0, fixture.engine.stopCalls)
        assertEquals(1, fixture.backstop.cancelCalls)
        val released = fixture.executions.sessions.getValue(owned.id)
        assertEquals(SessionStatus.FAILED, released.status)
        assertEquals(now.plusSeconds(96), released.cameraOwnershipReleasedAt)
        assertEquals(AutomationFailureCode.AUTOMATION_TIMEOUT, released.failure?.code)
        assertTrue(!released.ownsPixelCamera)
        assertEquals(
            listOf(
                "automation.rehearsal.stop_alarm_delivered",
                "automation.rehearsal.closed_without_recording",
            ),
            fixture.executions.events.map(AutomationEvent::name),
        )
        assertInstanceOf(RehearsalResult.Completed::class.java, fixture.coordinator.run(request))
    }

    @Test
    fun alarmReleasesCancelledRehearsalWithConsistentFailure() = runBlocking {
        val fixture = fixture(clockNow = now.plusSeconds(96))
        val rehearsal = fixture.executions.seedRehearsal(now, request, profile.id)
        fixture.executions.sessions[rehearsal.id] = rehearsal.copy(
            status = SessionStatus.CANCELLED,
            currentAutomationState = AutomationStateName.CANCELLED,
            recordActionAt = null,
            recordingVerifiedAt = null,
        )

        val result = fixture.alarmCoordinator.handle(
            RehearsalStopTrigger(rehearsal.id, rehearsal.expectedStopAt),
        )

        assertInstanceOf(AlarmHandlingResult.Accepted::class.java, result)
        val released = fixture.executions.sessions.getValue(rehearsal.id)
        assertEquals(SessionStatus.CANCELLED, released.status)
        assertEquals(AutomationFailureCode.AUTOMATION_CANCELLED, released.failure?.code)
        assertEquals(now.plusSeconds(96), released.cameraOwnershipReleasedAt)
        assertEquals(0, fixture.engine.stopCalls)
        assertEquals(1, fixture.backstop.cancelCalls)
    }

    @Test
    fun releaseConflictRetainsBackstopAndDuplicateAlarmDoesNotRepeatClosure() = runBlocking {
        val fixture = fixture(clockNow = now.plusSeconds(96))
        val rehearsal = fixture.executions.seedRehearsal(now, request, profile.id)
        fixture.executions.sessions[rehearsal.id] = rehearsal.copy(
            status = SessionStatus.PENDING,
            recordActionAt = null,
            recordingVerifiedAt = null,
        )
        fixture.executions.closeWithoutRecordingConflictsRemaining = 1
        val trigger = RehearsalStopTrigger(rehearsal.id, rehearsal.expectedStopAt)

        assertInstanceOf(AlarmHandlingResult.Retryable::class.java, fixture.alarmCoordinator.handle(trigger))
        assertEquals(0, fixture.backstop.cancelCalls)
        assertEquals(null, fixture.executions.sessions.getValue(rehearsal.id).cameraOwnershipReleasedAt)

        assertInstanceOf(AlarmHandlingResult.Accepted::class.java, fixture.alarmCoordinator.handle(trigger))
        val released = fixture.executions.sessions.getValue(rehearsal.id)
        assertEquals(now.plusSeconds(96), released.cameraOwnershipReleasedAt)
        assertEquals(1, fixture.backstop.cancelCalls)

        assertInstanceOf(AlarmHandlingResult.Accepted::class.java, fixture.alarmCoordinator.handle(trigger))
        assertEquals(released, fixture.executions.sessions.getValue(rehearsal.id))
        assertEquals(
            1,
            fixture.executions.events.count { it.name == "automation.rehearsal.closed_without_recording" },
        )
    }

    @Test
    fun scheduledCameraOwnerMakesRehearsalTypedBusyWithoutStarting() = runBlocking {
        val fixture = fixture()
        val owner = fixture.executions.seedRehearsal(now, request, profile.id).copy(
            kind = SessionKind.SCHEDULED,
            scheduleId = ScheduleId("scheduled-owner"),
            executionKey = "schedule/scheduled-owner",
        )
        fixture.executions.sessions.clear()
        fixture.executions.sessions[owner.id] = owner

        val result = fixture.coordinator.run(request)

        assertEquals(RehearsalResult.Busy(owner.id), result)
        assertEquals(0, fixture.engine.startCalls)
        assertEquals(listOf(owner), fixture.executions.sessions.values.toList())
    }

    private fun fixture(
        backstopScheduleFails: Boolean = false,
        completeStopProof: Boolean = true,
        delayFailure: Throwable? = null,
        replaceProfileDuringDelay: Boolean = false,
        clockNow: Instant = now,
    ): Fixture {
        val order = mutableListOf<String>()
        val executions = FakeRehearsalRepository()
        val profiles = FakeProfileRepository(profile)
        val backstop = FakeBackstop(order, backstopScheduleFails)
        val engine = FakeRehearsalEngine(executions, order, clockNow, completeStopProof)
        val mutex = Mutex()
        val stopWorkflow = RehearsalStopWorkflow(
            executionRepository = executions,
            environmentSnapshotRepository = executions,
            profileRepository = profiles,
            environmentProbe = { PortResult.Observed(environment) },
            automationEngine = engine,
            backstop = backstop,
            clock = LenswakeClock { clockNow },
            mutex = mutex,
        )
        val coordinator = DefaultRehearsalCoordinator(
            profileRepository = profiles,
            executionRepository = executions,
            environmentSnapshotRepository = executions,
            environmentSnapshotCollector = EnvironmentSnapshotCollector { id, sessionId ->
                Result.success(snapshot(id, sessionId))
            },
            environmentProbe = { PortResult.Observed(environment) },
            automationEngine = engine,
            backstop = backstop,
            stopWorkflow = stopWorkflow,
            clock = LenswakeClock { clockNow },
            mutex = mutex,
            rehearsalDelay = RehearsalDelay {
                order += "delay"
                if (replaceProfileDuringDelay) {
                    profiles.saved = profiles.saved.copy(selectorSchemaVersion = 2)
                }
                delayFailure?.let { throw it }
            },
        )
        return Fixture(
            coordinator = coordinator,
            alarmCoordinator = DefaultRehearsalStopTriggerCoordinator(stopWorkflow),
            executions = executions,
            profiles = profiles,
            engine = engine,
            backstop = backstop,
            order = order,
        )
    }

    private fun snapshot(id: EnvironmentSnapshotId, sessionId: SessionId) = EnvironmentSnapshot(
        id = id,
        sessionId = sessionId,
        capturedAt = now,
        lenswakeVersion = "test",
        cameraEnvironment = environment,
        accessibilityStatus = EnvironmentCapabilityStatus.AVAILABLE,
        privilegedBridgeStatus = EnvironmentCapabilityStatus.UNAVAILABLE,
        screenInteractive = true,
        keyguardLocked = false,
        batteryPercent = 80,
        charging = false,
        availableStorageBytes = 1_000_000,
    )

    private data class Fixture(
        val coordinator: DefaultRehearsalCoordinator,
        val alarmCoordinator: DefaultRehearsalStopTriggerCoordinator,
        val executions: FakeRehearsalRepository,
        val profiles: FakeProfileRepository,
        val engine: FakeRehearsalEngine,
        val backstop: FakeBackstop,
        val order: List<String>,
    )
}

private class FakeProfileRepository(initial: PixelCameraProfile) : AutomationProfileRepository {
    var saved: PixelCameraProfile = initial
    override fun observeProfiles(): Flow<List<PixelCameraProfile>> = flowOf(listOf(saved))
    override suspend fun get(id: ProfileId): PixelCameraProfile? = saved.takeIf { it.id == id }
    override suspend fun save(profile: PixelCameraProfile) { saved = profile }
    override suspend fun delete(id: ProfileId) = Unit
}

private class FakeRehearsalRepository : ExecutionRepository, EnvironmentSnapshotRepository {
    val sessions = linkedMapOf<SessionId, ExecutionSession>()
    val events = mutableListOf<AutomationEvent>()
    val snapshots = linkedMapOf<SessionId, EnvironmentSnapshot>()
    var failGet = false
    var closeWithoutRecordingConflictsRemaining = 0

    override fun observeExecutions(): Flow<List<ExecutionSession>> = flowOf(sessions.values.toList())
    override fun observeExecution(id: SessionId): Flow<ExecutionSession?> = flowOf(sessions[id])
    override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> =
        flowOf(events.filter { it.sessionId == sessionId })
    override suspend fun get(id: SessionId): ExecutionSession? {
        if (failGet) error("transient read failure")
        return sessions[id]
    }
    override suspend fun findPixelCameraOwnerForSchedule(scheduleId: ScheduleId): ExecutionSession? = null
    override suspend fun findActiveRehearsals(limit: Int): List<ExecutionSession> = sessions.values
        .filter { it.kind == SessionKind.REHEARSAL && it.status !in TERMINAL }
        .take(limit)
    override suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult {
        sessions.values.firstOrNull {
            it.id == session.id && it.executionKey == session.executionKey
        }?.let { return ExecutionReservationResult.Reserved(it, newlyCreated = false) }
        sessions.values.firstOrNull(ExecutionSession::ownsPixelCamera)?.let {
            return ExecutionReservationResult.CameraBusy(it)
        }
        check(sessions.putIfAbsent(session.id, session) == null)
        return ExecutionReservationResult.Reserved(session, newlyCreated = true)
    }
    override suspend fun apply(change: ExecutionChange, event: AutomationEvent): ExecutionApplyResult {
        val current = sessions[change.updatedSession.id]
        if (
            event.name == "automation.rehearsal.closed_without_recording" &&
            closeWithoutRecordingConflictsRemaining > 0
        ) {
            closeWithoutRecordingConflictsRemaining -= 1
            return ExecutionApplyResult.RevisionConflict(change.expectedRevision, current?.revision)
        }
        if (current?.revision != change.expectedRevision) {
            return ExecutionApplyResult.RevisionConflict(change.expectedRevision, current?.revision)
        }
        sessions[change.updatedSession.id] = change.updatedSession
        events += event
        return ExecutionApplyResult.Applied(change.updatedSession)
    }
    override suspend fun capture(snapshot: EnvironmentSnapshot): EnvironmentSnapshotCaptureResult {
        val existing = snapshots[snapshot.sessionId]
        if (existing != null) {
            return EnvironmentSnapshotCaptureResult.AlreadyExists(
                existing,
                sessions.getValue(snapshot.sessionId),
            )
        }
        val session = sessions.getValue(snapshot.sessionId)
        val linked = session.copy(
            environmentSnapshotId = snapshot.id,
            revision = session.revision + 1,
            updatedAt = maxOf(session.updatedAt, snapshot.capturedAt),
        )
        snapshots[snapshot.sessionId] = snapshot
        sessions[session.id] = linked
        return EnvironmentSnapshotCaptureResult.Captured(snapshot, linked)
    }
    override suspend fun getEnvironmentSnapshot(id: EnvironmentSnapshotId): EnvironmentSnapshot? =
        snapshots.values.firstOrNull { it.id == id }
    override suspend fun getEnvironmentSnapshotForSession(sessionId: SessionId): EnvironmentSnapshot? = snapshots[sessionId]
    override suspend fun report(sessionId: SessionId): ExecutionReport? = sessions[sessionId]?.let {
        ExecutionReport(it, snapshots[sessionId], events.filter { event -> event.sessionId == sessionId })
    }

    fun seedRehearsal(now: Instant, request: RehearsalRequest, profileId: ProfileId): ExecutionSession {
        val session = ExecutionSession(
            id = SessionId.new(),
            executionKey = "rehearsal/seed",
            kind = SessionKind.REHEARSAL,
            scheduleId = null,
            scheduleName = "Rehearsal",
            profileId = profileId,
            capture = request.capture,
            expectedStartAt = now,
            expectedStopAt = now.plusSeconds(95),
            status = SessionStatus.RECORDING,
            recordActionAt = now,
            recordingVerifiedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        sessions[session.id] = session
        return session
    }

    companion object {
        val TERMINAL = setOf(SessionStatus.COMPLETED, SessionStatus.FAILED, SessionStatus.CANCELLED)
    }
}

private fun ExecutionSession.ownsPixelCamera(): Boolean =
    status in setOf(
        SessionStatus.PENDING,
        SessionStatus.STARTING,
        SessionStatus.RECORDING,
        SessionStatus.STOPPING,
    ) || (status == SessionStatus.FAILED && recordActionAt != null && stoppedVerifiedAt == null)

private class FakeBackstop(
    private val order: MutableList<String>,
    private val scheduleFails: Boolean,
) : RehearsalStopBackstop {
    var cancelCalls = 0
    override suspend fun schedule(sessionId: SessionId): Result<Unit> {
        order += "schedule"
        return if (scheduleFails) Result.failure(IllegalStateException("unavailable")) else Result.success(Unit)
    }
    override suspend fun cancel(sessionId: SessionId): Result<Unit> {
        cancelCalls += 1
        order += "cancel"
        return Result.success(Unit)
    }
    override suspend fun restoreAll(): Result<Unit> = Result.success(Unit)
}

private class FakeRehearsalEngine(
    private val repository: FakeRehearsalRepository,
    private val order: MutableList<String>,
    private val now: Instant,
    private val completeStopProof: Boolean,
) : AutomationEngine {
    var startCalls = 0
    var stopCalls = 0

    override suspend fun start(sessionId: SessionId): AutomationRunResult {
        startCalls += 1
        order += "start"
        val current = repository.sessions.getValue(sessionId)
        val recording = current.copy(
            status = SessionStatus.RECORDING,
            recordActionAt = now.plusSeconds(1),
            recordingVerifiedAt = now.plusSeconds(2),
            revision = current.revision + 1,
            updatedAt = now.plusSeconds(2),
        )
        repository.sessions[sessionId] = recording
        return AutomationRunResult.Succeeded(recording)
    }

    override suspend fun stop(sessionId: SessionId): AutomationRunResult {
        stopCalls += 1
        order += "stop"
        val current = repository.sessions.getValue(sessionId)
        val completed = current.copy(
            status = SessionStatus.COMPLETED,
            stopActionAt = if (completeStopProof) now.plusSeconds(7) else null,
            stoppedVerifiedAt = now.plusSeconds(8),
            failure = null,
            revision = current.revision + 1,
            updatedAt = now.plusSeconds(8),
        )
        repository.sessions[sessionId] = completed
        return AutomationRunResult.Succeeded(completed)
    }
}
