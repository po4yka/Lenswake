package dev.po4yka.lenswake.application

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.alarm.AlarmHandlingResult
import dev.po4yka.lenswake.alarm.AlarmKind
import dev.po4yka.lenswake.alarm.AlarmTrigger
import dev.po4yka.lenswake.automation.AutomationEngine
import dev.po4yka.lenswake.automation.AutomationRunResult
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.TimeLapseSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
        val coordinator = coordinator(executions, engine)
        val trigger = startTrigger(schedule.updatedAt)

        assertTrue(coordinator.handle(trigger) is AlarmHandlingResult.Accepted)
        assertTrue(coordinator.handle(trigger) is AlarmHandlingResult.Accepted)

        assertEquals(1, executions.sessions.size)
        assertEquals(2, engine.startIds.size)
        assertEquals(engine.startIds.first(), engine.startIds.last())
        assertEquals(
            "schedule/${schedule.id.value}/${schedule.startAt.toEpochMilli()}",
            executions.sessions.values.single().executionKey,
        )
    }

    @Test
    fun obsoleteScheduleRevisionIsRejectedBeforePersistenceOrAutomation() = runBlocking {
        val executions = FakeExecutionRepository()
        val engine = FakeAutomationEngine(executions)
        val result = coordinator(executions, engine).handle(
            startTrigger(schedule.updatedAt.minusSeconds(1)),
        )

        assertTrue(result is AlarmHandlingResult.Rejected)
        assertTrue(executions.sessions.isEmpty())
        assertTrue(engine.startIds.isEmpty())
    }

    private fun coordinator(
        executions: FakeExecutionRepository,
        engine: FakeAutomationEngine,
    ): DefaultAlarmTriggerCoordinator = DefaultAlarmTriggerCoordinator(
        scheduleRepository = FakeScheduleRepository(schedule),
        executionRepository = executions,
        automationEngine = engine,
        clock = LenswakeClock { startAt.plusSeconds(1) },
    )

    private fun startTrigger(updatedAt: Instant) = AlarmTrigger(
        kind = AlarmKind.START,
        scheduleId = schedule.id,
        scheduleUpdatedAt = updatedAt,
        expectedAt = schedule.startAt,
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

private class FakeExecutionRepository : ExecutionRepository {
    val sessions = linkedMapOf<SessionId, ExecutionSession>()
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
    ): ExecutionApplyResult = error("Not used")
}

private class FakeAutomationEngine(
    private val executions: FakeExecutionRepository,
) : AutomationEngine {
    val startIds = mutableListOf<SessionId>()

    override suspend fun start(sessionId: SessionId): AutomationRunResult {
        startIds += sessionId
        return AutomationRunResult.AlreadySatisfied(requireNotNull(executions.get(sessionId)))
    }

    override suspend fun stop(sessionId: SessionId): AutomationRunResult = error("Not used")
}
