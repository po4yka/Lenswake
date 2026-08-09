package dev.po4yka.lenswake.core

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutionRepositoryRehearsalQueriesTest {
    @Test
    fun `default active rehearsal query includes outstanding failed ownership and is bounded`() = runTest {
        val pending = session("pending", SessionStatus.PENDING, stopAt = 30_000)
        val stopping = session("stopping", SessionStatus.STOPPING, stopAt = 10_000)
        val failedOwned = session(
            "failed-owned",
            SessionStatus.FAILED,
            stopAt = 20_000,
            recordActionAt = Instant.ofEpochMilli(5_000),
        )
        val failedUnowned = session("failed-unowned", SessionStatus.FAILED, stopAt = 5_000)
        val repository = FakeExecutionRepository(
            listOf(pending, stopping, failedOwned, failedUnowned),
        )

        assertEquals(
            listOf(stopping.id, failedOwned.id),
            repository.findActiveRehearsals(2).map(ExecutionSession::id),
        )
        assertTrue(
            runCatching { repository.findActiveRehearsals(0) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { repository.findActiveRehearsals(101) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `default latest successful rehearsal requires both verification proofs`() = runTest {
        val profileId = ProfileId("profile")
        val older = session(
            "older",
            SessionStatus.COMPLETED,
            stopAt = 10_000,
            profileId = profileId,
            recordingVerifiedAt = Instant.ofEpochMilli(7_000),
            stoppedVerifiedAt = Instant.ofEpochMilli(8_000),
        )
        val latest = session(
            "latest",
            SessionStatus.COMPLETED,
            stopAt = 20_000,
            profileId = profileId,
            recordingVerifiedAt = Instant.ofEpochMilli(17_000),
            stoppedVerifiedAt = Instant.ofEpochMilli(18_000),
        )
        val missingStop = session(
            "missing-stop",
            SessionStatus.COMPLETED,
            stopAt = 30_000,
            profileId = profileId,
            recordingVerifiedAt = Instant.ofEpochMilli(27_000),
        )
        val repository = FakeExecutionRepository(listOf(older, latest, missingStop))

        assertEquals(latest, repository.latestSuccessfulRehearsal(profileId))
        assertEquals(null, repository.latestSuccessfulRehearsal(ProfileId("absent")))
    }

    private fun session(
        id: String,
        status: SessionStatus,
        stopAt: Long,
        profileId: ProfileId = ProfileId("profile"),
        recordActionAt: Instant? = null,
        recordingVerifiedAt: Instant? = null,
        stoppedVerifiedAt: Instant? = null,
    ): ExecutionSession = ExecutionSession(
        id = SessionId(id),
        executionKey = "rehearsal/$id",
        kind = SessionKind.REHEARSAL,
        scheduleId = null,
        scheduleName = "Rehearsal",
        profileId = profileId,
        capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
        expectedStartAt = Instant.ofEpochMilli(stopAt - 1_000),
        expectedStopAt = Instant.ofEpochMilli(stopAt),
        status = status,
        recordActionAt = recordActionAt,
        recordingVerifiedAt = recordingVerifiedAt,
        stoppedVerifiedAt = stoppedVerifiedAt,
        createdAt = Instant.ofEpochMilli(1_000),
        updatedAt = stoppedVerifiedAt ?: Instant.ofEpochMilli(2_000),
    )
}

private class FakeExecutionRepository(
    sessions: List<ExecutionSession>,
) : ExecutionRepository {
    private val state = MutableStateFlow(sessions)

    override fun observeExecutions(): Flow<List<ExecutionSession>> = state

    override fun observeExecution(id: SessionId): Flow<ExecutionSession?> =
        flowOf(state.value.firstOrNull { it.id == id })

    override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> = flowOf(emptyList())

    override suspend fun get(id: SessionId): ExecutionSession? = state.value.firstOrNull { it.id == id }

    override suspend fun findActiveForSchedule(scheduleId: ScheduleId): ExecutionSession? = null

    override suspend fun create(session: ExecutionSession) {
        state.value += session
    }

    override suspend fun apply(
        change: ExecutionChange,
        event: AutomationEvent,
    ): ExecutionApplyResult = error("Not used by rehearsal query tests")
}
