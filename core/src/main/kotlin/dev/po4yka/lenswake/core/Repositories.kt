package dev.po4yka.lenswake.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant

interface ScheduleRepository {
    fun observeSchedules(): Flow<List<RecordingSchedule>>

    suspend fun get(id: ScheduleId): RecordingSchedule?

    suspend fun save(schedule: RecordingSchedule)

    suspend fun delete(id: ScheduleId)
}

interface AutomationProfileRepository {
    fun observeProfiles(): Flow<List<PixelCameraProfile>>

    suspend fun get(id: ProfileId): PixelCameraProfile?

    suspend fun save(profile: PixelCameraProfile)

    suspend fun delete(id: ProfileId)
}

sealed interface ExecutionReservationResult {
    data class Reserved(
        val session: ExecutionSession,
        val newlyCreated: Boolean,
    ) : ExecutionReservationResult

    data class CameraBusy(
        val owner: ExecutionSession,
    ) : ExecutionReservationResult
}

/**
 * Persistence boundary for recoverable automation execution.
 *
 * [apply] must compare [ExecutionChange.expectedRevision] with the stored revision and persist the
 * updated session and its event in one transaction. A conflict must not persist either value.
 */
interface ExecutionRepository {
    fun observeExecutions(): Flow<List<ExecutionSession>>

    fun observeExecution(id: SessionId): Flow<ExecutionSession?>

    fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>>

    suspend fun get(id: SessionId): ExecutionSession?

    /** Returns the schedule execution that currently owns, or may still own, Pixel Camera. */
    suspend fun findPixelCameraOwnerForSchedule(scheduleId: ScheduleId): ExecutionSession?

    /**
     * Atomically reserves global Pixel Camera ownership for [session].
     *
     * Implementations must return the existing matching execution idempotently, insert [session]
     * only when no execution owns Pixel Camera, or report the current owner. The ownership check
     * and insert must occur in one transaction.
     */
    suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult

    suspend fun apply(
        change: ExecutionChange,
        event: AutomationEvent,
    ): ExecutionApplyResult

    /**
     * Atomically terminalizes scheduled executions that could own Pixel Camera across reboot.
     * Implementations preserve dispatch evidence, set an explicit ownership-release timestamp,
     * and persist one typed event per changed session in the same transaction.
     */
    suspend fun reconcileInterruptedScheduledSessions(recoveredAt: Instant): List<ExecutionSession> =
        throw UnsupportedOperationException("Interrupted-session recovery is not implemented")

    /** Returns a bounded, stop-deadline-ordered recovery queue for durable rehearsals. */
    suspend fun findActiveRehearsals(limit: Int): List<ExecutionSession> {
        require(limit in 1..MAX_ACTIVE_REHEARSAL_LIMIT) {
            "Rehearsal query limit must be between 1 and $MAX_ACTIVE_REHEARSAL_LIMIT"
        }
        return observeExecutions().first()
            .asSequence()
            .filter { session ->
                session.kind == SessionKind.REHEARSAL && session.ownsPixelCamera
            }
            .sortedWith(compareBy(ExecutionSession::expectedStopAt, ExecutionSession::createdAt, { it.id.value }))
            .take(limit)
            .toList()
    }

    /** Returns the latest rehearsal with both start and stop verification for [profileId]. */
    suspend fun latestSuccessfulRehearsal(profileId: ProfileId): ExecutionSession? =
        observeExecutions().first()
            .asSequence()
            .filter { session ->
                session.kind == SessionKind.REHEARSAL &&
                    session.profileId == profileId &&
                    session.status == SessionStatus.COMPLETED &&
                    session.recordingVerifiedAt != null &&
                    session.stoppedVerifiedAt != null
            }
            .maxWithOrNull(
                compareBy<ExecutionSession>(
                    { checkNotNull(it.stoppedVerifiedAt) },
                    ExecutionSession::updatedAt,
                    { it.id.value },
                ),
            )

    companion object {
        const val MAX_ACTIVE_REHEARSAL_LIMIT: Int = 100

    }
}

/**
 * Persistence boundary for immutable execution diagnostics.
 *
 * A session may own at most one environment snapshot. [capture] never overwrites a previously
 * persisted snapshot, including when a caller retries with a different value. The first capture
 * atomically links the snapshot from its execution session and advances the session revision.
 */
interface EnvironmentSnapshotRepository {
    suspend fun capture(snapshot: EnvironmentSnapshot): EnvironmentSnapshotCaptureResult

    suspend fun getEnvironmentSnapshot(id: EnvironmentSnapshotId): EnvironmentSnapshot?

    suspend fun getEnvironmentSnapshotForSession(sessionId: SessionId): EnvironmentSnapshot?

    suspend fun report(sessionId: SessionId): ExecutionReport?
}

interface RecordingScheduler {
    suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit>

    suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit>

    suspend fun cancel(scheduleId: ScheduleId): Result<Unit>

    suspend fun restoreAll(): Result<Unit>
}
