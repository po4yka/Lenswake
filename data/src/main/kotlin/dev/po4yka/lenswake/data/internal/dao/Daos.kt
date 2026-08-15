package dev.po4yka.lenswake.data.internal.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.data.internal.entity.AutomationProfileEntity
import dev.po4yka.lenswake.data.internal.entity.EnvironmentSnapshotEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionEventEntity
import dev.po4yka.lenswake.data.internal.entity.ExecutionSessionEntity
import dev.po4yka.lenswake.data.internal.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.math.max

@Dao
internal interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY start_at_epoch_ms, id")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun get(id: String): ScheduleEntity?

    @Upsert
    suspend fun upsert(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
internal interface AutomationProfileDao {
    @Query("SELECT * FROM automation_profiles ORDER BY id")
    fun observeAll(): Flow<List<AutomationProfileEntity>>

    @Query("SELECT * FROM automation_profiles WHERE id = :id")
    suspend fun get(id: String): AutomationProfileEntity?

    @Upsert
    suspend fun upsert(profile: AutomationProfileEntity)

    @Query("DELETE FROM automation_profiles WHERE id = :id")
    suspend fun delete(id: String)
}

internal sealed interface ExecutionCasResult {
    data object Applied : ExecutionCasResult
    data class Conflict(val actualRevision: Long?) : ExecutionCasResult
}

internal sealed interface ExecutionReservationEntityResult {
    data class Reserved(
        val session: ExecutionSessionEntity,
        val newlyCreated: Boolean,
    ) : ExecutionReservationEntityResult

    data class CameraBusy(
        val owner: ExecutionSessionEntity,
    ) : ExecutionReservationEntityResult
}

internal interface ExecutionQueryDao {
    @Query("SELECT * FROM execution_sessions ORDER BY created_at_epoch_ms DESC, id DESC")
    fun observeAll(): Flow<List<ExecutionSessionEntity>>

    @Query("SELECT * FROM execution_sessions WHERE id = :id")
    fun observe(id: String): Flow<ExecutionSessionEntity?>

    @Query("SELECT * FROM execution_events WHERE session_id = :sessionId ORDER BY sequence, id")
    fun observeEvents(sessionId: String): Flow<List<ExecutionEventEntity>>

    @Query("SELECT * FROM execution_sessions WHERE id = :id")
    suspend fun get(id: String): ExecutionSessionEntity?

    @Query("SELECT * FROM execution_sessions WHERE execution_key = :executionKey")
    suspend fun getByExecutionKey(executionKey: String): ExecutionSessionEntity?

    @Query(
        """
        SELECT * FROM execution_sessions
        WHERE stopped_verified_at_epoch_ms IS NULL
          AND camera_ownership_released_at_epoch_ms IS NULL
          AND (
            status IN ('PENDING', 'STARTING', 'RECORDING', 'STOPPING')
            OR (status = 'FAILED' AND record_action_at_epoch_ms IS NOT NULL)
          )
        ORDER BY created_at_epoch_ms, id
        LIMIT 1
        """,
    )
    suspend fun findPixelCameraOwner(): ExecutionSessionEntity?

    @Query(
        """
        SELECT * FROM execution_sessions
        WHERE schedule_id = :scheduleId
          AND stopped_verified_at_epoch_ms IS NULL
          AND camera_ownership_released_at_epoch_ms IS NULL
          AND (
            status IN ('PENDING', 'STARTING', 'RECORDING', 'STOPPING')
            OR (status = 'FAILED' AND record_action_at_epoch_ms IS NOT NULL)
          )
        ORDER BY created_at_epoch_ms DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findPixelCameraOwnerForSchedule(scheduleId: String): ExecutionSessionEntity?

    @Query(
        """
        SELECT * FROM execution_sessions
        WHERE kind = 'REHEARSAL'
          AND (
            (
              stopped_verified_at_epoch_ms IS NULL
              AND camera_ownership_released_at_epoch_ms IS NULL
              AND (
                status IN ('PENDING', 'STARTING', 'RECORDING', 'STOPPING')
                OR (status = 'FAILED' AND record_action_at_epoch_ms IS NOT NULL)
              )
            )
            OR (
              stopped_verified_at_epoch_ms IS NOT NULL
              AND media_saved_verified_at_epoch_ms IS NULL
              AND media_baseline_generation IS NOT NULL
              AND media_store_version IS NOT NULL
              AND media_verification_required = 1
              AND status IN ('STOPPING', 'FAILED')
            )
          )
        ORDER BY expected_stop_at_epoch_ms, created_at_epoch_ms, id
        LIMIT :limit
        """,
    )
    suspend fun findActiveRehearsals(limit: Int): List<ExecutionSessionEntity>

    @Query(
        """
        SELECT * FROM execution_sessions
        WHERE kind = 'REHEARSAL'
          AND profile_id = :profileId
          AND status = 'COMPLETED'
          AND recording_verified_at_epoch_ms IS NOT NULL
          AND stopped_verified_at_epoch_ms IS NOT NULL
          AND media_saved_verified_at_epoch_ms IS NOT NULL
        ORDER BY stopped_verified_at_epoch_ms DESC, updated_at_epoch_ms DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestSuccessfulRehearsal(profileId: String): ExecutionSessionEntity?

    @Query(
        """
        SELECT * FROM execution_sessions
        WHERE kind = 'SCHEDULED'
          AND stopped_verified_at_epoch_ms IS NULL
          AND camera_ownership_released_at_epoch_ms IS NULL
          AND (
            status IN ('PENDING', 'STARTING', 'RECORDING', 'STOPPING')
            OR (status = 'FAILED' AND record_action_at_epoch_ms IS NOT NULL)
          )
        ORDER BY created_at_epoch_ms, id
        """,
    )
    suspend fun findInterruptedScheduledOwners(): List<ExecutionSessionEntity>
}

internal interface ExecutionMutationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(session: ExecutionSessionEntity): Long

    @Update
    suspend fun update(session: ExecutionSessionEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: ExecutionEventEntity)

    @Query("SELECT revision FROM execution_sessions WHERE id = :id")
    suspend fun revision(id: String): Long?

    @Query("SELECT COALESCE(MAX(sequence), -1) + 1 FROM execution_events WHERE session_id = :sessionId")
    suspend fun nextEventSequence(sessionId: String): Long
}

@Dao
internal interface ExecutionDao : ExecutionQueryDao, ExecutionMutationDao {

    @Transaction
    suspend fun reconcileInterruptedScheduledSessions(
        recoveredAtEpochMs: Long,
        failureCode: String,
        failureMessage: String,
    ): List<ExecutionSessionEntity> = findInterruptedScheduledOwners().map { session ->
        check(session.revision < Long.MAX_VALUE) {
            "Interrupted execution revision cannot be incremented"
        }
        val updatedAt = max(recoveredAtEpochMs, session.updatedAtEpochMs)
        val updated = session.copy(
            status = "FAILED",
            currentAutomationState = "FAILED",
            cameraOwnershipReleasedAtEpochMs = updatedAt,
            failureCode = failureCode,
            failureMessage = failureMessage,
            failureContextJson = "{\"recovery\":\"device_reboot\"}",
            revision = session.revision + 1,
            updatedAtEpochMs = updatedAt,
        )
        check(update(updated) == 1) {
            "Interrupted execution disappeared during recovery"
        }
        insertEvent(
            ExecutionEventEntity(
                id = UUID.randomUUID().toString(),
                sessionId = updated.id,
                name = "automation.execution.reboot_interrupted",
                sequence = nextEventSequence(updated.id),
                timestampEpochMs = updatedAt,
                state = "FAILED",
                operation = null,
                outcome = "FAILED",
                interactionMethod = null,
                attempt = null,
                durationMs = null,
                failureCode = failureCode,
                failureMessage = failureMessage,
                failureContextJson = "{\"recovery\":\"device_reboot\"}",
                metadataJson = "{\"recovery\":\"device_reboot\"}",
            ),
        )
        updated
    }

    @Transaction
    suspend fun reservePixelCamera(
        session: ExecutionSessionEntity,
    ): ExecutionReservationEntityResult {
        val existingById = get(session.id)
        val existingByKey = getByExecutionKey(session.executionKey)
        val existingReservation = if (existingById != null || existingByKey != null) {
            check(
                existingById?.executionKey == session.executionKey &&
                    existingByKey?.id == session.id,
            ) {
                "Execution session conflicts with an existing id or execution key"
            }
            ExecutionReservationEntityResult.Reserved(
                session = checkNotNull(existingById),
                newlyCreated = false,
            )
        } else {
            null
        }

        return existingReservation ?: findPixelCameraOwner()?.let { owner ->
            ExecutionReservationEntityResult.CameraBusy(owner)
        } ?: run {
            check(insertIgnoringConflict(session) != -1L) {
                "Execution reservation conflicted after the transactional ownership check"
            }
            ExecutionReservationEntityResult.Reserved(session, newlyCreated = true)
        }
    }

    @Transaction
    suspend fun compareAndSetWithEvent(
        expectedRevision: Long,
        session: ExecutionSessionEntity,
        eventWithoutSequence: ExecutionEventEntity,
        requestedSequence: Long?,
    ): ExecutionCasResult {
        val actualRevision = revision(session.id)
        if (actualRevision != expectedRevision) {
            return ExecutionCasResult.Conflict(actualRevision)
        }

        check(update(session) == 1) { "Execution session disappeared during transactional update" }
        val event = eventWithoutSequence.copy(
            sequence = requestedSequence ?: nextEventSequence(session.id),
        )
        insertEvent(event)
        return ExecutionCasResult.Applied
    }
}

internal data class ExecutionReportEntities(
    val session: ExecutionSessionEntity,
    val environmentSnapshot: EnvironmentSnapshotEntity?,
    val events: List<ExecutionEventEntity>,
)

internal sealed interface EnvironmentSnapshotInsertResult {
    data class Inserted(
        val session: ExecutionSessionEntity,
    ) : EnvironmentSnapshotInsertResult

    data class AlreadyExists(
        val existing: EnvironmentSnapshotEntity,
        val session: ExecutionSessionEntity,
    ) : EnvironmentSnapshotInsertResult
}

@Dao
internal interface EnvironmentSnapshotDao {
    @Query("SELECT * FROM environment_snapshots WHERE id = :id")
    suspend fun get(id: String): EnvironmentSnapshotEntity?

    @Query("SELECT * FROM environment_snapshots WHERE session_id = :sessionId")
    suspend fun getForSession(sessionId: String): EnvironmentSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(snapshot: EnvironmentSnapshotEntity): Long

    @Query("SELECT * FROM execution_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): ExecutionSessionEntity?

    @Query("SELECT * FROM execution_events WHERE session_id = :sessionId ORDER BY sequence, id")
    suspend fun listEvents(sessionId: String): List<ExecutionEventEntity>

    @Query(
        """
        UPDATE execution_sessions
        SET environment_snapshot_id = :snapshotId,
            revision = revision + 1,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE id = :sessionId
          AND revision = :expectedRevision
          AND environment_snapshot_id IS NULL
        """,
    )
    suspend fun linkSnapshot(
        sessionId: String,
        snapshotId: String,
        expectedRevision: Long,
        updatedAtEpochMs: Long,
    ): Int

    @Transaction
    suspend fun insertImmutable(snapshot: EnvironmentSnapshotEntity): EnvironmentSnapshotInsertResult {
        val session = checkNotNull(getSession(snapshot.sessionId)) {
            "Environment snapshot session does not exist"
        }
        check(snapshot.matchesCapture(session)) {
            "Environment snapshot video settings do not match its execution capture"
        }
        val existingForSession = getForSession(snapshot.sessionId)
        if (existingForSession != null) {
            check(session.environmentSnapshotId == existingForSession.id) {
                "Execution session snapshot pointer does not match its immutable snapshot"
            }
            return EnvironmentSnapshotInsertResult.AlreadyExists(existingForSession, session)
        }

        val existingById = get(snapshot.id)
        if (existingById != null) {
            error("Environment snapshot id is already owned by another execution session")
        }

        check(session.environmentSnapshotId == null) {
            "Execution session points to a missing environment snapshot"
        }
        check(insertIgnoringConflict(snapshot) != -1L) {
            "Environment snapshot changed concurrently inside a transaction"
        }
        val updatedAtEpochMs = maxOf(session.updatedAtEpochMs, snapshot.capturedAtEpochMs)
        check(
            linkSnapshot(
                sessionId = session.id,
                snapshotId = snapshot.id,
                expectedRevision = session.revision,
                updatedAtEpochMs = updatedAtEpochMs,
            ) == 1,
        ) {
            "Execution session changed concurrently while linking its environment snapshot"
        }
        val updatedSession = checkNotNull(getSession(session.id)) {
            "Execution session disappeared while linking its environment snapshot"
        }
        return EnvironmentSnapshotInsertResult.Inserted(updatedSession)
    }

    @Transaction
    suspend fun report(sessionId: String): ExecutionReportEntities? {
        val session = getSession(sessionId) ?: return null
        return ExecutionReportEntities(
            session = session,
            environmentSnapshot = getForSession(sessionId),
            events = listEvents(sessionId),
        )
    }
}

private fun EnvironmentSnapshotEntity.matchesCapture(session: ExecutionSessionEntity): Boolean =
    if (session.captureType == CaptureMode.VIDEO.name) {
        videoResolution == session.videoResolution && videoFrameRate == session.videoFrameRate
    } else {
        videoResolution == null && videoFrameRate == null
    }
