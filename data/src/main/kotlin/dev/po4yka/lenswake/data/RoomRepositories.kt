package dev.po4yka.lenswake.data

import android.util.Log
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.CorruptProfileEntryException
import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EnvironmentSnapshotCaptureResult
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.EnvironmentSnapshotRepository
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionReport
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfilePersistenceIssue
import dev.po4yka.lenswake.core.ProfilePersistenceIssueCode
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.data.internal.dao.ExecutionCasResult
import dev.po4yka.lenswake.data.internal.dao.ExecutionDao
import dev.po4yka.lenswake.data.internal.dao.ExecutionReservationEntityResult
import dev.po4yka.lenswake.data.internal.dao.EnvironmentSnapshotInsertResult
import dev.po4yka.lenswake.data.internal.entity.AutomationProfileEntity
import dev.po4yka.lenswake.data.internal.mapping.toDomain
import dev.po4yka.lenswake.data.internal.mapping.toEntity
import java.time.Instant
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
        dao.observeAll().map { profiles -> profiles.decode(reportIssues = true).profiles }

    override fun observePersistenceIssues(): Flow<List<ProfilePersistenceIssue>> =
        dao.observeAll().map { profiles -> profiles.decode(reportIssues = false).issues }

    override suspend fun get(id: ProfileId): PixelCameraProfile? = dao.get(id.value)?.let { entity ->
        try {
            entity.toDomain()
        } catch (error: IllegalArgumentException) {
            val issue = entity.persistenceIssue()
            report(issue, error)
            throw CorruptProfileEntryException(issue, error)
        }
    }

    override suspend fun save(profile: PixelCameraProfile) {
        dao.upsert(profile.toEntity())
    }

    override suspend fun delete(id: ProfileId) {
        dao.delete(id.value)
    }

    private fun List<AutomationProfileEntity>.decode(
        reportIssues: Boolean,
    ): DecodedProfiles {
        val profiles = ArrayList<PixelCameraProfile>(size)
        val issues = mutableListOf<ProfilePersistenceIssue>()
        for (entity in this) {
            try {
                profiles += entity.toDomain()
            } catch (error: IllegalArgumentException) {
                val issue = entity.persistenceIssue()
                issues += issue
                if (reportIssues) report(issue, error)
            }
        }
        return DecodedProfiles(profiles, issues)
    }

    private fun AutomationProfileEntity.persistenceIssue() = ProfilePersistenceIssue(
        entryKey = id,
        code = ProfilePersistenceIssueCode.CORRUPT_ENTRY,
    )

    private fun report(issue: ProfilePersistenceIssue, error: Exception) {
        Log.e(
            TAG,
            "profile.persistence.corrupt_entry " +
                "code=${issue.code} cause=${error.javaClass.simpleName}",
        )
    }

    private data class DecodedProfiles(
        val profiles: List<PixelCameraProfile>,
        val issues: List<ProfilePersistenceIssue>,
    )

    private companion object {
        const val TAG = "LenswakeProfileStore"
    }
}

class RoomExecutionRepository private constructor(
    private val dao: ExecutionDao,
    environmentSnapshotRepository: EnvironmentSnapshotRepository,
) : ExecutionRepository,
    EnvironmentSnapshotRepository by environmentSnapshotRepository {
    constructor(database: LenswakeDatabase) : this(
        dao = database.executionDao(),
        environmentSnapshotRepository = RoomEnvironmentSnapshotRepository(database),
    )

    override fun observeExecutions(): Flow<List<ExecutionSession>> =
        dao.observeAll().map { executions -> executions.map { it.toDomain() } }

    override fun observeExecution(id: SessionId): Flow<ExecutionSession?> =
        dao.observe(id.value).map { it?.toDomain() }

    override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> =
        dao.observeEvents(sessionId.value).map { events -> events.map { it.toDomain() } }

    override suspend fun get(id: SessionId): ExecutionSession? = dao.get(id.value)?.toDomain()

    override suspend fun findPixelCameraOwnerForSchedule(scheduleId: ScheduleId): ExecutionSession? =
        dao.findPixelCameraOwnerForSchedule(scheduleId.value)?.toDomain()

    override suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult =
        when (val result = dao.reservePixelCamera(session.toEntity())) {
            is ExecutionReservationEntityResult.Reserved -> ExecutionReservationResult.Reserved(
                session = result.session.toDomain(),
                newlyCreated = result.newlyCreated,
            )
            is ExecutionReservationEntityResult.CameraBusy ->
                ExecutionReservationResult.CameraBusy(result.owner.toDomain())
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

    override suspend fun findActiveRehearsals(limit: Int): List<ExecutionSession> {
        require(limit in 1..ExecutionRepository.MAX_ACTIVE_REHEARSAL_LIMIT) {
            "Rehearsal query limit must be between 1 and " +
                ExecutionRepository.MAX_ACTIVE_REHEARSAL_LIMIT
        }
        return dao.findActiveRehearsals(limit).map { it.toDomain() }
    }

    override suspend fun latestSuccessfulRehearsal(profileId: ProfileId): ExecutionSession? =
        dao.findLatestSuccessfulRehearsal(profileId.value)?.toDomain()

    override suspend fun reconcileInterruptedScheduledSessions(
        recoveredAt: Instant,
    ): List<ExecutionSession> = dao.reconcileInterruptedScheduledSessions(
        recoveredAtEpochMs = recoveredAt.toEpochMilli(),
        failureCode = AutomationFailureCode.DEVICE_REBOOT_INTERRUPTED.name,
        failureMessage = "Device reboot interrupted Pixel Camera execution; STOP was not verified",
    ).map { it.toDomain() }
}

private class RoomEnvironmentSnapshotRepository(
    database: LenswakeDatabase,
) : EnvironmentSnapshotRepository {
    private val dao = database.environmentSnapshotDao()

    override suspend fun capture(snapshot: EnvironmentSnapshot): EnvironmentSnapshotCaptureResult =
        when (val result = dao.insertImmutable(snapshot.toEntity())) {
            is EnvironmentSnapshotInsertResult.Inserted ->
                EnvironmentSnapshotCaptureResult.Captured(
                    snapshot = snapshot,
                    session = result.session.toDomain(),
                )
            is EnvironmentSnapshotInsertResult.AlreadyExists ->
                EnvironmentSnapshotCaptureResult.AlreadyExists(
                    existing = result.existing.toDomain(),
                    session = result.session.toDomain(),
                )
        }

    override suspend fun getEnvironmentSnapshot(id: EnvironmentSnapshotId): EnvironmentSnapshot? =
        dao.get(id.value)?.toDomain()

    override suspend fun getEnvironmentSnapshotForSession(sessionId: SessionId): EnvironmentSnapshot? =
        dao.getForSession(sessionId.value)?.toDomain()

    override suspend fun report(sessionId: SessionId): ExecutionReport? =
        dao.report(sessionId.value)?.let { report ->
            ExecutionReport(
                session = report.session.toDomain(),
                environmentSnapshot = report.environmentSnapshot?.toDomain(),
                events = report.events.map { it.toDomain() },
            )
        }
}
