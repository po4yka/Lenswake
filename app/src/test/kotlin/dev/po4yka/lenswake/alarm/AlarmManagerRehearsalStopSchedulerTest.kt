package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmManagerRehearsalStopSchedulerTest {
    private val now = Instant.parse("2026-08-09T10:00:00Z")

    @Test
    fun scheduleFailsClosedWhenSessionIsMissingOrExactAlarmsAreUnavailable() = runBlocking {
        val repository = FakeRehearsalExecutionRepository()
        val backend = FakeRehearsalStopAlarmBackend(canSchedule = false)
        val scheduler = scheduler(repository, backend)

        assertEquals(
            SchedulingFailureCode.REHEARSAL_SESSION_NOT_PERSISTED,
            scheduler.schedule(SessionId("missing")).schedulingFailureCode(),
        )

        val rehearsal = session("persisted", expectedStopAt = now.plusSeconds(60))
        repository.sessions.value = listOf(rehearsal)
        assertEquals(
            SchedulingFailureCode.EXACT_ALARM_UNAVAILABLE,
            scheduler.schedule(rehearsal.id).schedulingFailureCode(),
        )
        assertTrue(backend.scheduled.isEmpty())
    }

    @Test
    fun scheduleRejectsNonRehearsalAndPastStop() = runBlocking {
        val scheduled = session(
            id = "scheduled",
            expectedStopAt = now.plusSeconds(60),
            kind = SessionKind.SCHEDULED,
        )
        val past = session("past", expectedStopAt = now.minusSeconds(1))
        val repository = FakeRehearsalExecutionRepository(listOf(scheduled, past))
        val scheduler = scheduler(repository, FakeRehearsalStopAlarmBackend())

        assertEquals(
            SchedulingFailureCode.NOT_A_REHEARSAL_SESSION,
            scheduler.schedule(scheduled.id).schedulingFailureCode(),
        )
        assertEquals(
            SchedulingFailureCode.REHEARSAL_STOP_NOT_IN_FUTURE,
            scheduler.schedule(past.id).schedulingFailureCode(),
        )
    }

    @Test
    fun scheduleRegistersOriginalPersistedStopDeadline() = runBlocking {
        val rehearsal = session("future", expectedStopAt = now.plusSeconds(60))
        val backend = FakeRehearsalStopAlarmBackend()
        val scheduler = scheduler(FakeRehearsalExecutionRepository(listOf(rehearsal)), backend)

        assertTrue(scheduler.schedule(rehearsal.id).isSuccess)

        assertEquals(
            ScheduledRehearsalAlarm(
                RehearsalStopTrigger(rehearsal.id, rehearsal.expectedStopAt),
                rehearsal.expectedStopAt,
            ),
            backend.scheduled.single(),
        )
    }

    @Test
    fun restoreKeepsFutureBackstopsAndImmediatelyRearmsEveryOwnedOverdueStop() = runBlocking {
        val future = session("future", expectedStopAt = now.plusSeconds(60))
        val overdueOwned = session(
            "overdue-owned",
            expectedStopAt = now.minusSeconds(30),
            recordActionAt = now.minusSeconds(90),
            status = SessionStatus.FAILED,
        )
        val overdueUndispatched = session("overdue-undispatched", expectedStopAt = now.minusSeconds(20))
        val alreadyStopped = session(
            "stopped",
            expectedStopAt = now.minusSeconds(10),
            recordActionAt = now.minusSeconds(90),
            stoppedVerifiedAt = now.minusSeconds(5),
            status = SessionStatus.STOPPING,
        )
        val active = listOf(future, overdueOwned, overdueUndispatched, alreadyStopped)
        val repository = FakeRehearsalExecutionRepository(active).also { it.activeRehearsals = active }
        val backend = FakeRehearsalStopAlarmBackend()

        assertTrue(scheduler(repository, backend).restoreAll().isSuccess)

        assertEquals(listOf(alreadyStopped.id), backend.cancelled)
        assertEquals(3, backend.scheduled.size)
        assertEquals(future.expectedStopAt, backend.scheduled[0].triggerAt)
        assertEquals(overdueOwned.expectedStopAt, backend.scheduled[1].trigger.expectedAt)
        assertEquals(now.plusMillis(1_000), backend.scheduled[1].triggerAt)
        assertEquals(overdueUndispatched.expectedStopAt, backend.scheduled[2].trigger.expectedAt)
        assertEquals(now.plusMillis(1_000), backend.scheduled[2].triggerAt)
    }

    private fun scheduler(
        repository: ExecutionRepository,
        backend: RehearsalStopAlarmBackend,
    ) = AlarmManagerRehearsalStopScheduler(repository, { now }, backend)

    private fun session(
        id: String,
        expectedStopAt: Instant,
        kind: SessionKind = SessionKind.REHEARSAL,
        recordActionAt: Instant? = null,
        stoppedVerifiedAt: Instant? = null,
        status: SessionStatus = SessionStatus.PENDING,
    ): ExecutionSession = ExecutionSession(
        id = SessionId(id),
        executionKey = "execution-$id",
        kind = kind,
        scheduleId = if (kind == SessionKind.SCHEDULED) ScheduleId("schedule-$id") else null,
        scheduleName = null,
        profileId = ProfileId("profile"),
        capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X30),
        expectedStartAt = expectedStopAt.minusSeconds(60),
        expectedStopAt = expectedStopAt,
        status = status,
        recordActionAt = recordActionAt,
        stoppedVerifiedAt = stoppedVerifiedAt,
        createdAt = now.minusSeconds(120),
        updatedAt = now.minusSeconds(60),
    )
}

private data class ScheduledRehearsalAlarm(
    val trigger: RehearsalStopTrigger,
    val triggerAt: Instant,
)

private class FakeRehearsalStopAlarmBackend(
    private val canSchedule: Boolean = true,
) : RehearsalStopAlarmBackend {
    val scheduled = mutableListOf<ScheduledRehearsalAlarm>()
    val cancelled = mutableListOf<SessionId>()

    override fun canScheduleExactAlarms(): Boolean = canSchedule

    override fun schedule(trigger: RehearsalStopTrigger, triggerAt: Instant): Result<Unit> {
        scheduled += ScheduledRehearsalAlarm(trigger, triggerAt)
        return Result.success(Unit)
    }

    override fun cancel(sessionId: SessionId): Result<Unit> {
        cancelled += sessionId
        return Result.success(Unit)
    }
}

private class FakeRehearsalExecutionRepository(
    initial: List<ExecutionSession> = emptyList(),
) : ExecutionRepository {
    val sessions = MutableStateFlow(initial)
    var activeRehearsals: List<ExecutionSession> = initial

    override fun observeExecutions(): Flow<List<ExecutionSession>> = sessions

    override fun observeExecution(id: SessionId): Flow<ExecutionSession?> =
        flowOf(sessions.value.singleOrNull { it.id == id })

    override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> =
        flowOf(emptyList())

    override suspend fun get(id: SessionId): ExecutionSession? =
        sessions.value.singleOrNull { it.id == id }

    override suspend fun findPixelCameraOwnerForSchedule(scheduleId: ScheduleId): ExecutionSession? = null

    override suspend fun findActiveRehearsals(limit: Int): List<ExecutionSession> =
        activeRehearsals.take(limit)

    override suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult =
        ExecutionReservationResult.Reserved(session, newlyCreated = true)

    override suspend fun apply(
        change: ExecutionChange,
        event: AutomationEvent,
    ): ExecutionApplyResult = ExecutionApplyResult.RevisionConflict(
        expectedRevision = change.expectedRevision,
        actualRevision = null,
    )
}

private fun Result<Unit>.schedulingFailureCode(): SchedulingFailureCode? =
    (exceptionOrNull() as? SchedulingException)?.code
