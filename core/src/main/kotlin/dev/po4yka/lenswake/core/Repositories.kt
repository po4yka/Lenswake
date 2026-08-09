package dev.po4yka.lenswake.core

import kotlinx.coroutines.flow.Flow

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
}

interface RecordingScheduler {
    suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit>

    suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit>

    suspend fun cancel(scheduleId: ScheduleId): Result<Unit>

    suspend fun restoreAll(): Result<Unit>
}
