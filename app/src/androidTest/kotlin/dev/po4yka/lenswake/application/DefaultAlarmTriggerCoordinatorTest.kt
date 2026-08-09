package dev.po4yka.lenswake.application

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.alarm.AlarmHandlingResult
import dev.po4yka.lenswake.alarm.AlarmKind
import dev.po4yka.lenswake.alarm.AlarmTrigger
import dev.po4yka.lenswake.automation.AutomationEngine
import dev.po4yka.lenswake.automation.AutomationRunResult
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReport
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.EnvironmentCapabilityStatus
import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EnvironmentSnapshotCaptureResult
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.EnvironmentSnapshotRepository
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DefaultAlarmTriggerCoordinatorTest {
    private val startAt = Instant.parse("2026-08-10T05:30:00Z")
    private val stopAt = Instant.parse("2026-08-10T07:30:00Z")
    private val schedule = RecordingSchedule(
        id = ScheduleId("morning"),
        name = "Morning time lapse",
        startAt = startAt,
        stopAt = stopAt,
        zoneId = ZoneId.of("UTC"),
        capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X30),
        profileId = ProfileId("pixel-profile"),
        enabled = true,
        createdAt = Instant.parse("2026-08-09T10:00:00Z"),
        updatedAt = Instant.parse("2026-08-09T11:00:00Z"),
    )

    @Test
    fun duplicateStartUsesOneDeterministicPersistedSession() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions)
        val collector = FakeEnvironmentSnapshotCollector()
        val coordinator = coordinator(executions, engine, collector = collector)
        val trigger = startTrigger(schedule.updatedAt)

        assertTrue(coordinator.handle(trigger) is AlarmHandlingResult.Accepted)
        assertTrue(coordinator.handle(trigger) is AlarmHandlingResult.Accepted)

        assertEquals(1, executions.sessions.size)
        assertEquals(2, engine.startIds.size)
        assertEquals(engine.startIds.first(), engine.startIds.last())
        assertEquals(listOf(true, true), engine.startSawPersistedSnapshot)
        assertEquals(1, collector.calls)
        assertEquals(1, executions.snapshots.size)
        assertEquals(
            "schedule/${schedule.id.value}/${schedule.startAt.toEpochMilli()}",
            executions.sessions.values.single().executionKey,
        )
    }

    @Test
    fun snapshotCaptureFailureRejectsStartBeforeAutomation() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions)
        val collector = FakeEnvironmentSnapshotCollector(failure = IllegalStateException("probe failed"))

        val result = coordinator(executions, engine, collector = collector).handle(
            startTrigger(schedule.updatedAt),
        )

        assertTrue(result is AlarmHandlingResult.Retryable)
        assertTrue(engine.startIds.isEmpty())
        assertTrue(executions.snapshots.isEmpty())
    }

    @Test
    fun hangingSnapshotCollectorTimesOutBeforeAutomation() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions)
        val hangingCollector = EnvironmentSnapshotCollector { _, _ -> awaitCancellation() }

        val result = coordinator(
            executions = executions,
            engine = engine,
            collector = hangingCollector,
            snapshotTimeoutMillis = 25,
        ).handle(startTrigger(schedule.updatedAt))

        assertTrue(result is AlarmHandlingResult.Retryable)
        assertTrue(engine.startIds.isEmpty())
        assertTrue(executions.snapshots.isEmpty())
    }

    @Test
    fun obsoleteScheduleRevisionIsRejectedBeforePersistenceOrAutomation() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions)
        val result = coordinator(executions, engine).handle(
            startTrigger(schedule.updatedAt.minusSeconds(1)),
        )

        assertTrue(result is AlarmHandlingResult.TerminalRejected)
        assertTrue(executions.sessions.isEmpty())
        assertTrue(engine.startIds.isEmpty())
    }

    @Test
    fun enginePersistenceFailureIsRetryable() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions) { session ->
            AutomationRunResult.PersistenceFailure(
                session = session,
                failure = AutomationFailure(
                    AutomationFailureCode.SESSION_PERSISTENCE_FAILED,
                    "database unavailable",
                ),
            )
        }

        val result = coordinator(executions, engine).handle(startTrigger(schedule.updatedAt))

        assertTrue(result is AlarmHandlingResult.Retryable)
    }

    @Test
    fun engineRevisionConflictIsRetryable() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions) { session ->
            AutomationRunResult.RevisionConflict(
                session = session,
                expectedRevision = session.revision,
                actualRevision = session.revision + 1,
            )
        }

        val result = coordinator(executions, engine).handle(startTrigger(schedule.updatedAt))

        assertTrue(result is AlarmHandlingResult.Retryable)
    }

    @Test
    fun uncertainRecordingStartIsRetryableForInspectOnlyReconciliation() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions) { session ->
            AutomationRunResult.StartReconciliationRequired(
                session = session.copy(recordActionAt = startAt.plusSeconds(1)),
                failure = AutomationFailure(
                    AutomationFailureCode.AUTOMATION_TIMEOUT,
                    "Record may have been dispatched",
                ),
            )
        }

        val result = coordinator(executions, engine).handle(startTrigger(schedule.updatedAt))

        assertTrue(result is AlarmHandlingResult.Retryable)
    }

    @Test
    fun persistedEngineFailureIsTerminal() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions) { session ->
            AutomationRunResult.Failed(
                session = session,
                failure = AutomationFailure(
                    AutomationFailureCode.RECORDING_NOT_CONFIRMED,
                    "recording was not verified",
                ),
            )
        }

        val result = coordinator(executions, engine).handle(startTrigger(schedule.updatedAt))

        assertTrue(result is AlarmHandlingResult.TerminalRejected)
    }

    @Test
    fun stopDeliveryIsPersistedAtomicallyBeforeAutomation() = runBlocking {
        val executions = FakeExecutionRepository().apply { seed(recordingSession()) }
        val engine = FakeAutomationEngine(executions)
        val result = coordinator(
            executions = executions,
            engine = engine,
            now = stopAt.plusSeconds(1),
        ).handle(stopTrigger())

        assertTrue(result is AlarmHandlingResult.Accepted)
        assertEquals(listOf(true), engine.stopSawPersistedDelivery)
        assertEquals(stopAt.plusSeconds(1), executions.sessions.values.single().alarmStopDeliveredAt)
        assertEquals(1, executions.events.size)
        assertEquals("automation.alarm.stop_delivered", executions.events.single().name)
    }

    @Test
    fun duplicateStopPersistsOneDeliveryEventAndRemainsIdempotent() = runBlocking {
        val executions = FakeExecutionRepository().apply { seed(recordingSession()) }
        val engine = FakeAutomationEngine(executions)
        val coordinator = coordinator(
            executions = executions,
            engine = engine,
            now = stopAt.plusSeconds(1),
        )

        assertTrue(coordinator.handle(stopTrigger()) is AlarmHandlingResult.Accepted)
        assertTrue(coordinator.handle(stopTrigger()) is AlarmHandlingResult.Accepted)

        assertEquals(1, executions.events.size)
        assertEquals(1L, executions.sessions.values.single().revision)
        assertEquals(listOf(true, true), engine.stopSawPersistedDelivery)
    }

    private fun coordinator(
        executions: FakeExecutionRepository,
        engine: FakeAutomationEngine,
        now: Instant = startAt.plusSeconds(1),
        collector: EnvironmentSnapshotCollector = FakeEnvironmentSnapshotCollector(),
        snapshotTimeoutMillis: Long = 5_000,
    ): DefaultAlarmTriggerCoordinator = DefaultAlarmTriggerCoordinator(
        scheduleRepository = FakeScheduleRepository(schedule),
        executionRepository = executions,
        environmentSnapshotRepository = executions,
        environmentSnapshotCollector = collector,
        automationEngine = engine,
        clock = LenswakeClock { now },
        snapshotCollectionTimeoutMillis = snapshotTimeoutMillis,
    )

    private fun startTrigger(updatedAt: Instant) = AlarmTrigger(
        kind = AlarmKind.START,
        scheduleId = schedule.id,
        scheduleUpdatedAt = updatedAt,
        expectedAt = schedule.startAt,
    )

    private fun stopTrigger() = AlarmTrigger(
        kind = AlarmKind.STOP,
        scheduleId = schedule.id,
        scheduleUpdatedAt = schedule.updatedAt,
        expectedAt = schedule.stopAt,
    )

    private fun recordingSession() = ExecutionSession(
        id = SessionId("scheduled-session"),
        executionKey = "schedule/${schedule.id.value}/${schedule.startAt.toEpochMilli()}",
        kind = SessionKind.SCHEDULED,
        scheduleId = schedule.id,
        scheduleName = schedule.name,
        profileId = schedule.profileId,
        capture = schedule.capture,
        expectedStartAt = schedule.startAt,
        expectedStopAt = schedule.stopAt,
        alarmStartDeliveredAt = startAt,
        status = SessionStatus.RECORDING,
        recordingVerifiedAt = startAt.plusSeconds(10),
        revision = 0,
        createdAt = startAt,
        updatedAt = startAt.plusSeconds(10),
    )
}

private class FakeScheduleRepository(
    private val schedule: RecordingSchedule,
) : ScheduleRepository {
    override fun observeSchedules(): Flow<List<RecordingSchedule>> = flowOf(listOf(schedule))
    override suspend fun get(id: ScheduleId): RecordingSchedule? = schedule.takeIf { it.id == id }
    override suspend fun save(schedule: RecordingSchedule) = error("Not used")
    override suspend fun delete(id: ScheduleId) = error("Not used")
}

private class FakeExecutionRepository : ExecutionRepository, EnvironmentSnapshotRepository {
    val sessions = linkedMapOf<SessionId, ExecutionSession>()
    val events = mutableListOf<AutomationEvent>()
    val snapshots = linkedMapOf<EnvironmentSnapshotId, EnvironmentSnapshot>()
    private val observed = MutableStateFlow<List<ExecutionSession>>(emptyList())

    override fun observeExecutions(): Flow<List<ExecutionSession>> = observed
    override fun observeExecution(id: SessionId): Flow<ExecutionSession?> = flowOf(sessions[id])
    override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> = flowOf(emptyList())
    override suspend fun get(id: SessionId): ExecutionSession? = sessions[id]
    override suspend fun findActiveForSchedule(scheduleId: ScheduleId): ExecutionSession? =
        sessions.values.firstOrNull { it.scheduleId == scheduleId }

    override suspend fun create(session: ExecutionSession) {
        sessions.putIfAbsent(session.id, session)
        observed.value = sessions.values.toList()
    }

    override suspend fun apply(
        change: ExecutionChange,
        event: AutomationEvent,
    ): ExecutionApplyResult {
        val current = sessions[change.updatedSession.id]
        if (current?.revision != change.expectedRevision) {
            return ExecutionApplyResult.RevisionConflict(
                expectedRevision = change.expectedRevision,
                actualRevision = current?.revision,
            )
        }
        sessions[change.updatedSession.id] = change.updatedSession
        events += event
        observed.value = sessions.values.toList()
        return ExecutionApplyResult.Applied(change.updatedSession)
    }

    fun seed(session: ExecutionSession) {
        sessions[session.id] = session
        observed.value = sessions.values.toList()
    }

    override suspend fun capture(snapshot: EnvironmentSnapshot): EnvironmentSnapshotCaptureResult {
        val session = requireNotNull(sessions[snapshot.sessionId])
        val existing = snapshots.values.firstOrNull { it.sessionId == snapshot.sessionId }
        if (existing != null) {
            return EnvironmentSnapshotCaptureResult.AlreadyExists(existing, session)
        }
        snapshots[snapshot.id] = snapshot
        val linked = session.copy(
            environmentSnapshotId = snapshot.id,
            revision = session.revision + 1,
            updatedAt = maxOf(session.updatedAt, snapshot.capturedAt),
        )
        sessions[session.id] = linked
        observed.value = sessions.values.toList()
        return EnvironmentSnapshotCaptureResult.Captured(snapshot, linked)
    }

    override suspend fun getEnvironmentSnapshot(id: EnvironmentSnapshotId): EnvironmentSnapshot? = snapshots[id]

    override suspend fun getEnvironmentSnapshotForSession(sessionId: SessionId): EnvironmentSnapshot? =
        snapshots.values.firstOrNull { it.sessionId == sessionId }

    override suspend fun report(sessionId: SessionId): ExecutionReport? = sessions[sessionId]?.let { session ->
        ExecutionReport(
            session = session,
            environmentSnapshot = getEnvironmentSnapshotForSession(sessionId),
            events = events.filter { it.sessionId == sessionId },
        )
    }
}

private class FakeAutomationEngine(
    private val executions: FakeExecutionRepository,
    private val startResult: ((ExecutionSession) -> AutomationRunResult)? = null,
) : AutomationEngine {
    val startIds = mutableListOf<SessionId>()
    val startSawPersistedSnapshot = mutableListOf<Boolean>()
    val stopSawPersistedDelivery = mutableListOf<Boolean>()

    override suspend fun start(sessionId: SessionId): AutomationRunResult {
        startIds += sessionId
        val session = requireNotNull(executions.get(sessionId))
        startSawPersistedSnapshot += session.environmentSnapshotId != null &&
            executions.getEnvironmentSnapshotForSession(sessionId) != null
        return startResult?.invoke(session) ?: AutomationRunResult.AlreadySatisfied(session)
    }

    override suspend fun stop(sessionId: SessionId): AutomationRunResult {
        val session = requireNotNull(executions.get(sessionId))
        stopSawPersistedDelivery += session.alarmStopDeliveredAt != null && executions.events.any {
            it.sessionId == sessionId && it.name == "automation.alarm.stop_delivered"
        }
        return AutomationRunResult.Succeeded(session)
    }
}

private class FakeEnvironmentSnapshotCollector(
    private val failure: Throwable? = null,
) : EnvironmentSnapshotCollector {
    var calls: Int = 0

    override suspend fun collect(
        snapshotId: EnvironmentSnapshotId,
        sessionId: SessionId,
    ): Result<EnvironmentSnapshot> {
        calls += 1
        failure?.let { return Result.failure(it) }
        return Result.success(
            EnvironmentSnapshot(
                id = snapshotId,
                sessionId = sessionId,
                capturedAt = Instant.parse("2026-08-10T05:30:01Z"),
                lenswakeVersion = "test",
                cameraEnvironment = PixelCameraEnvironment(
                    deviceManufacturer = "Google",
                    deviceModel = "Pixel 8 Pro",
                    androidSdk = 37,
                    androidBuildFingerprint = "test/fingerprint",
                    cameraPackage = "com.google.android.GoogleCamera",
                    cameraVersionCode = 1,
                    localeTag = "en-US",
                    displayWidthPx = 1344,
                    displayHeightPx = 2992,
                    densityDpi = 489,
                ),
                accessibilityStatus = EnvironmentCapabilityStatus.AVAILABLE,
                privilegedBridgeStatus = EnvironmentCapabilityStatus.UNAVAILABLE,
                screenInteractive = true,
                keyguardLocked = true,
                batteryPercent = 80,
                charging = false,
                availableStorageBytes = 1_000_000,
            ),
        )
    }
}
