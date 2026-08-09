package dev.po4yka.lenswake.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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

    suspend fun findActiveForSchedule(scheduleId: ScheduleId): ExecutionSession?

    suspend fun create(session: ExecutionSession)

    suspend fun apply(
        change: ExecutionChange,
        event: AutomationEvent,
    ): ExecutionApplyResult

    /** Returns a bounded, stop-deadline-ordered recovery queue for durable rehearsals. */
    suspend fun findActiveRehearsals(limit: Int): List<ExecutionSession> {
        require(limit in 1..MAX_ACTIVE_REHEARSAL_LIMIT) {
            "Rehearsal query limit must be between 1 and $MAX_ACTIVE_REHEARSAL_LIMIT"
        }
        return observeExecutions().first()
            .asSequence()
            .filter { session ->
                session.kind == SessionKind.REHEARSAL && (
                    session.status in ACTIVE_REHEARSAL_STATUSES ||
                        (
                            session.status == SessionStatus.FAILED &&
                                session.recordActionAt != null &&
                                session.stoppedVerifiedAt == null
                            )
                    )
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

        private val ACTIVE_REHEARSAL_STATUSES = setOf(
            SessionStatus.PENDING,
            SessionStatus.STARTING,
            SessionStatus.RECORDING,
            SessionStatus.STOPPING,
        )
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
