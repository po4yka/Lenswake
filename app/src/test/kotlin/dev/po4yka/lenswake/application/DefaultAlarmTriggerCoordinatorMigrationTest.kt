package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.alarm.AlarmHandlingResult
import dev.po4yka.lenswake.alarm.AlarmKind
import dev.po4yka.lenswake.alarm.AlarmTrigger
import dev.po4yka.lenswake.automation.AutomationEngine
import dev.po4yka.lenswake.automation.AutomationRunResult
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.CaptureConfiguration
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
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DefaultAlarmTriggerCoordinatorMigrationTest {
    private val startAt = Instant.parse("2026-08-10T05:30:00Z")
    private val stopAt = Instant.parse("2026-08-10T07:30:00Z")
    private val deliveredAt = stopAt.plusSeconds(1)
    private val schedule = RecordingSchedule(
        id = ScheduleId("migrated-schedule"),
        name = "Migrated recording",
        startAt = startAt,
        stopAt = stopAt,
        zoneId = ZoneId.of("UTC"),
        capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X30),
        profileId = ProfileId("legacy-profile"),
        enabled = false,
        createdAt = startAt.minusSeconds(3_600),
        updatedAt = startAt.minusSeconds(1_800),
    )

    @Test
    fun `disabled migrated schedule still delivers stop for its persisted camera owner`() = runTest {
        val executions = CoordinatorRepositories(recordingSession())
        val engine = RecordingAutomationEngine(executions)

        val result = coordinator(executions, engine).handle(stopTrigger())

        assertInstanceOf(AlarmHandlingResult.Accepted::class.java, result)
        assertEquals(listOf(recordingSession().id), engine.stopCalls)
        assertEquals(deliveredAt, executions.session?.alarmStopDeliveredAt)
    }

    @Test
    fun `disabled schedule without a camera owner remains rejected`() = runTest {
        val executions = CoordinatorRepositories(session = null)
        val engine = RecordingAutomationEngine(executions)

        val result = coordinator(executions, engine).handle(stopTrigger())

        assertInstanceOf(AlarmHandlingResult.TerminalRejected::class.java, result)
        assertEquals(emptyList<SessionId>(), engine.stopCalls)
    }

    private fun coordinator(
        executions: CoordinatorRepositories,
        engine: RecordingAutomationEngine,
    ) = DefaultAlarmTriggerCoordinator(
        scheduleRepository = SingleScheduleRepository(schedule),
        executionRepository = executions,
        environmentSnapshotRepository = executions,
        environmentSnapshotCollector = EnvironmentSnapshotCollector { _, _ -> error("Not used for STOP") },
        automationEngine = engine,
        startReadiness = { Result.success(Unit) },
        clock = LenswakeClock { deliveredAt },
    )

    private fun stopTrigger() = AlarmTrigger(
        kind = AlarmKind.STOP,
        scheduleId = schedule.id,
        scheduleUpdatedAt = schedule.updatedAt,
        expectedAt = schedule.stopAt,
    )

    private fun recordingSession() = ExecutionSession(
        id = SessionId("migrated-owner"),
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
        recordActionAt = startAt.plusSeconds(9),
        recordingVerifiedAt = startAt.plusSeconds(10),
        createdAt = startAt,
        updatedAt = startAt.plusSeconds(10),
    )
}

private class SingleScheduleRepository(
    private val schedule: RecordingSchedule,
) : ScheduleRepository {
    override fun observeSchedules(): Flow<List<RecordingSchedule>> = flowOf(listOf(schedule))
    override suspend fun get(id: ScheduleId): RecordingSchedule? = schedule.takeIf { it.id == id }
    override suspend fun save(schedule: RecordingSchedule) = error("Not used")
    override suspend fun delete(id: ScheduleId) = error("Not used")
}

private class CoordinatorRepositories(
    var session: ExecutionSession?,
) : ExecutionRepository, EnvironmentSnapshotRepository {
    override fun observeExecutions(): Flow<List<ExecutionSession>> = flowOf(listOfNotNull(session))
    override fun observeExecution(id: SessionId): Flow<ExecutionSession?> = flowOf(session?.takeIf { it.id == id })
    override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> = flowOf(emptyList())
    override suspend fun get(id: SessionId): ExecutionSession? = session?.takeIf { it.id == id }

    override suspend fun findPixelCameraOwnerForSchedule(scheduleId: ScheduleId): ExecutionSession? =
        session?.takeIf { it.scheduleId == scheduleId && it.ownsPixelCamera }

    override suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult =
        error("Not used for STOP")

    override suspend fun apply(
        change: ExecutionChange,
        event: AutomationEvent,
    ): ExecutionApplyResult {
        val current = checkNotNull(session)
        return if (current.revision == change.expectedRevision) {
            session = change.updatedSession
            ExecutionApplyResult.Applied(change.updatedSession)
        } else {
            ExecutionApplyResult.RevisionConflict(change.expectedRevision, current.revision)
        }
    }

    override suspend fun capture(snapshot: EnvironmentSnapshot): EnvironmentSnapshotCaptureResult =
        error("Not used for STOP")

    override suspend fun getEnvironmentSnapshot(id: EnvironmentSnapshotId): EnvironmentSnapshot? = null
    override suspend fun getEnvironmentSnapshotForSession(sessionId: SessionId): EnvironmentSnapshot? = null
    override suspend fun report(sessionId: SessionId): ExecutionReport? = null
}

private class RecordingAutomationEngine(
    private val executions: CoordinatorRepositories,
) : AutomationEngine {
    val stopCalls = mutableListOf<SessionId>()

    override suspend fun start(sessionId: SessionId): AutomationRunResult = error("Not used")

    override suspend fun stop(sessionId: SessionId): AutomationRunResult {
        stopCalls += sessionId
        return AutomationRunResult.Succeeded(checkNotNull(executions.session))
    }
}
