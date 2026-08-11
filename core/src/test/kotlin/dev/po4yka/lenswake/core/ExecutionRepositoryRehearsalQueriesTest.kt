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
        val awaitingMedia = session(
            "awaiting-media",
            SessionStatus.FAILED,
            stopAt = 8_000,
            recordingVerifiedAt = Instant.ofEpochMilli(4_000),
            mediaBaselineGeneration = 41,
            mediaStoreVersion = "version-1",
            stoppedVerifiedAt = Instant.ofEpochMilli(7_000),
        )
        val awaitingReceipt = verifiedSession(
            id = "awaiting-receipt",
            stopAt = 9_000,
        ).copy(rehearsalVerifiedAt = null)
        val repository = FakeExecutionRepository(
            listOf(pending, stopping, failedOwned, failedUnowned, awaitingMedia, awaitingReceipt),
        )

        assertEquals(
            listOf(awaitingMedia.id, awaitingReceipt.id, stopping.id, failedOwned.id),
            repository.findActiveRehearsals(4).map(ExecutionSession::id),
        )
        assertTrue(
            runCatching { repository.findActiveRehearsals(0) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { repository.findActiveRehearsals(101) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `default latest successful rehearsal requires full proof and durable receipt`() = runTest {
        val profileId = ProfileId("profile")
        val older = verifiedSession(
            id = "older",
            stopAt = 10_000,
            profileId = profileId,
        )
        val latest = verifiedSession(
            id = "latest",
            stopAt = 20_000,
            profileId = profileId,
        )
        val missingStop = verifiedSession(
            id = "missing-stop",
            stopAt = 30_000,
            profileId = profileId,
        ).copy(stopActionAt = null)
        val missingRecord = verifiedSession(
            id = "missing-record",
            stopAt = 35_000,
            profileId = profileId,
        ).copy(recordActionAt = null)
        val missingMedia = verifiedSession(
            id = "missing-media",
            stopAt = 40_000,
            profileId = profileId,
        ).copy(mediaSavedVerifiedAt = null)
        val missingReceipt = verifiedSession(
            id = "missing-receipt",
            stopAt = 50_000,
            profileId = profileId,
        ).copy(rehearsalVerifiedAt = null)
        val repository = FakeExecutionRepository(
            listOf(older, latest, missingStop, missingRecord, missingMedia, missingReceipt),
        )

        assertEquals(latest, repository.latestSuccessfulRehearsal(profileId))
        assertEquals(
            latest,
            repository.latestSuccessfulRehearsal(
                profileId,
                CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
            ),
        )
        assertEquals(
            null,
            repository.latestSuccessfulRehearsal(profileId, CaptureConfiguration.Video()),
        )
        assertEquals(null, repository.latestSuccessfulRehearsal(ProfileId("absent")))
    }

    @Test
    fun `exact rehearsal query rejects profile and capture mismatches`() = runTest {
        val expectedCapture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X120,
            lens = LensSelection.REAR_TELEPHOTO,
        )
        val verified = verifiedSession(
            id = "verified",
            stopAt = 10_000,
            capture = expectedCapture,
        )
        val repository = FakeExecutionRepository(listOf(verified))

        assertEquals(
            verified,
            repository.latestSuccessfulRehearsal(ProfileId("profile"), expectedCapture),
        )
        assertEquals(
            null,
            repository.latestSuccessfulRehearsal(
                ProfileId("different-profile"),
                expectedCapture,
            ),
        )
        assertEquals(
            null,
            repository.latestSuccessfulRehearsal(
                ProfileId("profile"),
                expectedCapture.copy(lens = LensSelection.REAR_MAIN),
            ),
        )
    }

    private fun verifiedSession(
        id: String,
        stopAt: Long,
        profileId: ProfileId = ProfileId("profile"),
        capture: CaptureConfiguration = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
    ): ExecutionSession = session(
        id = id,
        status = SessionStatus.COMPLETED,
        stopAt = stopAt,
        profileId = profileId,
        recordActionAt = Instant.ofEpochMilli(stopAt - 5_000),
        recordingVerifiedAt = Instant.ofEpochMilli(stopAt - 4_000),
        stopActionAt = Instant.ofEpochMilli(stopAt - 3_000),
        stoppedVerifiedAt = Instant.ofEpochMilli(stopAt - 2_000),
        mediaSavedVerifiedAt = Instant.ofEpochMilli(stopAt - 1_000),
        rehearsalVerifiedAt = Instant.ofEpochMilli(stopAt),
        capture = capture,
    )

    private fun session(
        id: String,
        status: SessionStatus,
        stopAt: Long,
        profileId: ProfileId = ProfileId("profile"),
        recordActionAt: Instant? = null,
        recordingVerifiedAt: Instant? = null,
        mediaBaselineGeneration: Long? = null,
        mediaStoreVersion: String? = null,
        mediaSavedVerifiedAt: Instant? = null,
        stopActionAt: Instant? = null,
        stoppedVerifiedAt: Instant? = null,
        rehearsalVerifiedAt: Instant? = null,
        capture: CaptureConfiguration = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
    ): ExecutionSession = ExecutionSession(
        id = SessionId(id),
        executionKey = "rehearsal/$id",
        kind = SessionKind.REHEARSAL,
        scheduleId = null,
        scheduleName = "Rehearsal",
        profileId = profileId,
        capture = capture,
        expectedStartAt = Instant.ofEpochMilli(stopAt - 1_000),
        expectedStopAt = Instant.ofEpochMilli(stopAt),
        status = status,
        recordActionAt = recordActionAt,
        recordingVerifiedAt = recordingVerifiedAt,
        mediaBaselineGeneration = mediaBaselineGeneration,
        mediaStoreVersion = mediaStoreVersion,
        mediaSavedVerifiedAt = mediaSavedVerifiedAt,
        stopActionAt = stopActionAt,
        stoppedVerifiedAt = stoppedVerifiedAt,
        rehearsalVerifiedAt = rehearsalVerifiedAt,
        createdAt = Instant.ofEpochMilli(1_000),
        updatedAt = rehearsalVerifiedAt ?: stoppedVerifiedAt ?: Instant.ofEpochMilli(2_000),
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

    override suspend fun findPixelCameraOwnerForSchedule(scheduleId: ScheduleId): ExecutionSession? = null

    override suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult {
        state.value += session
        return ExecutionReservationResult.Reserved(session, newlyCreated = true)
    }

    override suspend fun apply(
        change: ExecutionChange,
        event: AutomationEvent,
    ): ExecutionApplyResult = error("Not used by rehearsal query tests")
}
