package dev.po4yka.lenswake.data

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.data.internal.dao.ExecutionCasResult
import dev.po4yka.lenswake.data.internal.mapping.toDomain
import dev.po4yka.lenswake.data.internal.mapping.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomScheduleRepository(
    database: LenswakeDatabase,
) : ScheduleRepository {
    private val dao = database.scheduleDao()

    override fun observeSchedules(): Flow<List<RecordingSchedule>> =
        dao.observeAll().map { schedules -> schedules.map { it.toDomain() } }

    override suspend fun get(id: ScheduleId): RecordingSchedule? = dao.get(id.value)?.toDomain()

    override suspend fun save(schedule: RecordingSchedule) {
        dao.upsert(schedule.toEntity())
    }

    override suspend fun delete(id: ScheduleId) {
        dao.delete(id.value)
    }
}

class RoomAutomationProfileRepository(
    database: LenswakeDatabase,
) : AutomationProfileRepository {
    private val dao = database.automationProfileDao()

    override fun observeProfiles(): Flow<List<PixelCameraProfile>> =
        dao.observeAll().map { profiles -> profiles.map { it.toDomain() } }

    override suspend fun get(id: ProfileId): PixelCameraProfile? = dao.get(id.value)?.toDomain()

    override suspend fun save(profile: PixelCameraProfile) {
        dao.upsert(profile.toEntity())
    }

    override suspend fun delete(id: ProfileId) {
        dao.delete(id.value)
    }
}

class RoomExecutionRepository(
    database: LenswakeDatabase,
) : ExecutionRepository {
    private val dao = database.executionDao()

    override fun observeExecutions(): Flow<List<ExecutionSession>> =
        dao.observeAll().map { executions -> executions.map { it.toDomain() } }

    override fun observeExecution(id: SessionId): Flow<ExecutionSession?> =
        dao.observe(id.value).map { it?.toDomain() }

    override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> =
        dao.observeEvents(sessionId.value).map { events -> events.map { it.toDomain() } }

    override suspend fun get(id: SessionId): ExecutionSession? = dao.get(id.value)?.toDomain()

    override suspend fun findActiveForSchedule(scheduleId: ScheduleId): ExecutionSession? =
        dao.findActiveForSchedule(scheduleId.value)?.toDomain()

    override suspend fun create(session: ExecutionSession) {
        dao.createIdempotently(session.toEntity())
    }

    override suspend fun apply(
        change: ExecutionChange,
        event: AutomationEvent,
    ): ExecutionApplyResult {
        val updated = change.updatedSession
        require(event.sessionId == updated.id) {
            "Execution event must belong to the updated session"
        }

        return when (
            val result = dao.compareAndSetWithEvent(
                expectedRevision = change.expectedRevision,
                session = updated.toEntity(),
                eventWithoutSequence = event.toEntity(),
                requestedSequence = event.sequence,
            )
        ) {
            ExecutionCasResult.Applied -> ExecutionApplyResult.Applied(updated)
            is ExecutionCasResult.Conflict -> ExecutionApplyResult.RevisionConflict(
                expectedRevision = change.expectedRevision,
                actualRevision = result.actualRevision,
            )
        }
    }
}
