package dev.po4yka.lenswake.core

import java.time.Instant

data class ExecutionSession(
    val id: SessionId,
    val executionKey: String,
    val kind: SessionKind,
    val scheduleId: ScheduleId?,
    val scheduleName: String?,
    val profileId: ProfileId,
    val capture: CaptureConfiguration,
    val expectedStartAt: Instant,
    val expectedStopAt: Instant,
    val alarmStartDeliveredAt: Instant? = null,
    val alarmStopDeliveredAt: Instant? = null,
    val status: SessionStatus,
    val currentAutomationState: AutomationStateName? = null,
    /**
     * Write-ahead checkpoint persisted before invoking the external Record action.
     *
     * A value means dispatch may have occurred; it is ownership evidence for reconciliation, not
     * proof that Pixel Camera accepted the action or started recording. Only
     * [recordingVerifiedAt] proves the recording postcondition. A definitive rejected dispatch may
     * clear this marker, while timeout, exception, or cancellation must preserve it.
     */
    val recordActionAt: Instant? = null,
    val recordingVerifiedAt: Instant? = null,
    /** MediaStore generation captured before Record dispatch for durable output correlation. */
    val mediaBaselineGeneration: Long? = null,
    /** Opaque MediaStore volume version that makes [mediaBaselineGeneration] comparable. */
    val mediaStoreVersion: String? = null,
    /** False only for executions already in flight when saved-media verification was introduced. */
    val mediaVerificationRequired: Boolean = true,
    /** Proof that a new published Pixel Camera video appeared after [mediaBaselineGeneration]. */
    val mediaSavedVerifiedAt: Instant? = null,
    val savedMediaGeneration: Long? = null,
    val stopActionAt: Instant? = null,
    val stoppedVerifiedAt: Instant? = null,
    /** Explicit ownership release when external STOP verification is impossible, such as reboot. */
    val cameraOwnershipReleasedAt: Instant? = null,
    val environmentSnapshotId: EnvironmentSnapshotId? = null,
    val failure: AutomationFailure? = null,
    val revision: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(executionKey.isNotBlank()) { "Execution key must not be blank" }
        require(kind != SessionKind.SCHEDULED || scheduleId != null) {
            "Scheduled execution must reference its schedule"
        }
        require(expectedStopAt.isAfter(expectedStartAt)) {
            "Expected stop must be after expected start"
        }
        require(revision >= 0) { "Execution revision must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "Execution update cannot precede creation" }
    }

    val ownsPixelCamera: Boolean
        get() = stoppedVerifiedAt == null &&
            cameraOwnershipReleasedAt == null &&
            (status in ACTIVE_CAMERA_OWNERSHIP_STATUSES ||
                (status == SessionStatus.FAILED && recordActionAt != null))

    val awaitsMediaSaveVerification: Boolean
        get() = stoppedVerifiedAt != null &&
            mediaSavedVerifiedAt == null &&
            mediaBaselineGeneration != null &&
            mediaStoreVersion != null &&
            mediaVerificationRequired &&
            status in setOf(SessionStatus.STOPPING, SessionStatus.FAILED)

    private companion object {
        val ACTIVE_CAMERA_OWNERSHIP_STATUSES = setOf(
            SessionStatus.PENDING,
            SessionStatus.STARTING,
            SessionStatus.RECORDING,
            SessionStatus.STOPPING,
        )
    }
}

enum class SessionKind {
    SCHEDULED,
    REHEARSAL,
}

/**
 * A compare-and-set update to an [ExecutionSession].
 *
 * The repository applies this change only if the stored revision equals [expectedRevision].
 * [updatedSession] must carry the next consecutive revision, preventing callers from silently
 * skipping or reusing a state transition.
 */
data class ExecutionChange(
    val expectedRevision: Long,
    val updatedSession: ExecutionSession,
) {
    init {
        require(expectedRevision >= 0) { "Expected revision must not be negative" }
        require(expectedRevision < Long.MAX_VALUE) { "Expected revision cannot be incremented" }
        require(updatedSession.revision == expectedRevision + 1) {
            "Updated session revision must immediately follow expected revision"
        }
    }
}

sealed interface ExecutionApplyResult {
    data class Applied(
        val session: ExecutionSession,
    ) : ExecutionApplyResult

    data class RevisionConflict(
        val expectedRevision: Long,
        val actualRevision: Long?,
    ) : ExecutionApplyResult
}

enum class SessionStatus {
    PENDING,
    STARTING,
    RECORDING,
    STOPPING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class AutomationStateName {
    START_TRIGGERED,
    VALIDATING_SESSION,
    CAPTURING_ENVIRONMENT,
    CHECKING_PREREQUISITES,
    WAKING_DEVICE,
    LAUNCHING_SECURE_CAMERA,
    WAITING_FOR_PIXEL_CAMERA,
    INSPECTING_CAMERA_STATE,
    SELECTING_VIDEO,
    VERIFYING_VIDEO,
    SELECTING_TIME_LAPSE,
    VERIFYING_TIME_LAPSE,
    SELECTING_REAR_MAIN_LENS,
    VERIFYING_REAR_MAIN_LENS,
    OPENING_TIME_LAPSE_SPEED_CONTROL,
    VERIFYING_TIME_LAPSE_SPEED_CONTROL,
    SELECTING_SPEED,
    VERIFYING_SPEED,
    CLOSING_TIME_LAPSE_SPEED_CONTROL,
    VERIFYING_TIME_LAPSE_SPEED_CLOSED,
    STARTING_RECORDING,
    VERIFYING_RECORDING,
    RECORDING,
    CAPTURING_MEDIA_BASELINE,
    STOP_TRIGGERED,
    VALIDATING_ACTIVE_SESSION,
    INSPECTING_DEVICE,
    WAKING_IF_REQUIRED,
    LOCATING_PIXEL_CAMERA,
    INSPECTING_RECORDING_STATE,
    STOPPING_RECORDING,
    VERIFYING_STOPPED,
    VERIFYING_MEDIA_SAVED,
    COMPLETED,
    RETRYING,
    FAILED,
    CANCELLED,
}

enum class AutomationOperation {
    WAKE_DEVICE,
    LAUNCH_CAMERA,
    INSPECT_CAMERA,
    SELECT_VIDEO,
    SELECT_TIME_LAPSE,
    SELECT_REAR_MAIN_LENS,
    OPEN_TIME_LAPSE_SPEED_CONTROL,
    SELECT_TIME_LAPSE_SPEED,
    CLOSE_TIME_LAPSE_SPEED_CONTROL,
    START_RECORDING,
    STOP_RECORDING,
    VERIFY_RECORDING,
    VERIFY_STOPPED,
    CAPTURE_MEDIA_BASELINE,
    VERIFY_MEDIA_SAVED,
}

enum class AutomationOutcome {
    STARTED,
    DISPATCHED,
    SUCCEEDED,
    RETRYING,
    FAILED,
    CANCELLED,
}

enum class InteractionMethod {
    STANDARD_ANDROID_API,
    ACCESSIBILITY_ACTION,
    ACCESSIBILITY_NODE_GESTURE,
    ACCESSIBILITY_PROFILE_GESTURE,
    PRIVILEGED_INPUT,
}

data class AutomationEvent(
    val id: EventId,
    val sessionId: SessionId,
    val name: String,
    val sequence: Long? = null,
    val timestamp: Instant,
    val state: AutomationStateName,
    val operation: AutomationOperation? = null,
    val outcome: AutomationOutcome,
    val interactionMethod: InteractionMethod? = null,
    val attempt: Int? = null,
    val durationMs: Long? = null,
    val failure: AutomationFailure? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "Event name must not be blank" }
        require(sequence == null || sequence >= 0) { "Event sequence must not be negative" }
        require(attempt == null || attempt > 0) { "Event attempt must be positive" }
        require(durationMs == null || durationMs >= 0) { "Event duration must not be negative" }
        require(metadata.size <= MAX_METADATA_ENTRIES) {
            "Event metadata may contain at most $MAX_METADATA_ENTRIES entries"
        }
        require(metadata.all { (key, value) -> key.length <= MAX_METADATA_LENGTH && value.length <= MAX_METADATA_LENGTH }) {
            "Event metadata keys and values may contain at most $MAX_METADATA_LENGTH characters"
        }
    }

    companion object {
        const val MAX_METADATA_ENTRIES: Int = 24
        const val MAX_METADATA_LENGTH: Int = 256
    }
}
