package dev.po4yka.lenswake.data.internal.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "automation_profiles")
internal data class AutomationProfileEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "device_manufacturer")
    val deviceManufacturer: String,
    @ColumnInfo(name = "device_model")
    val deviceModel: String,
    @ColumnInfo(name = "android_sdk")
    val androidSdk: Int,
    @ColumnInfo(name = "android_build_fingerprint")
    val androidBuildFingerprint: String?,
    @ColumnInfo(name = "camera_package")
    val cameraPackage: String,
    @ColumnInfo(name = "camera_version_code")
    val cameraVersionCode: Long,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    @ColumnInfo(name = "display_width_px")
    val displayWidthPx: Int,
    @ColumnInfo(name = "display_height_px")
    val displayHeightPx: Int,
    @ColumnInfo(name = "density_dpi")
    val densityDpi: Int,
    @ColumnInfo(name = "selector_schema_version")
    val selectorSchemaVersion: Int,
    @ColumnInfo(name = "targets_json")
    val targetsJson: String,
    @ColumnInfo(name = "speed_targets_json")
    val speedTargetsJson: String,
    @ColumnInfo(name = "state_signals_json")
    val stateSignalsJson: String,
    @ColumnInfo(name = "fallback_gestures_json")
    val fallbackGesturesJson: String,
    val compatibility: String,
    @ColumnInfo(name = "verified_at_epoch_ms")
    val verifiedAtEpochMs: Long?,
)

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = AutomationProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onUpdate = ForeignKey.RESTRICT,
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["profile_id"])],
)
internal data class ScheduleEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "start_at_epoch_ms")
    val startAtEpochMs: Long,
    @ColumnInfo(name = "stop_at_epoch_ms")
    val stopAtEpochMs: Long,
    @ColumnInfo(name = "zone_id")
    val zoneId: String,
    @ColumnInfo(name = "capture_type")
    val captureType: String,
    @ColumnInfo(name = "time_lapse_speed")
    val timeLapseSpeed: String,
    @ColumnInfo(name = "lens_selection")
    val lensSelection: String,
    @ColumnInfo(name = "zoom_factor")
    val zoomFactor: Float?,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "execution_sessions",
    indices = [
        Index(value = ["execution_key"], unique = true),
        Index(value = ["schedule_id"]),
        Index(value = ["status"]),
    ],
)
internal data class ExecutionSessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "execution_key")
    val executionKey: String,
    val kind: String,
    @ColumnInfo(name = "schedule_id")
    val scheduleId: String?,
    @ColumnInfo(name = "schedule_name")
    val scheduleName: String?,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "capture_type")
    val captureType: String,
    @ColumnInfo(name = "time_lapse_speed")
    val timeLapseSpeed: String,
    @ColumnInfo(name = "lens_selection")
    val lensSelection: String,
    @ColumnInfo(name = "zoom_factor")
    val zoomFactor: Float?,
    @ColumnInfo(name = "expected_start_at_epoch_ms")
    val expectedStartAtEpochMs: Long,
    @ColumnInfo(name = "expected_stop_at_epoch_ms")
    val expectedStopAtEpochMs: Long,
    @ColumnInfo(name = "alarm_start_delivered_at_epoch_ms")
    val alarmStartDeliveredAtEpochMs: Long?,
    @ColumnInfo(name = "alarm_stop_delivered_at_epoch_ms")
    val alarmStopDeliveredAtEpochMs: Long?,
    val status: String,
    @ColumnInfo(name = "current_automation_state")
    val currentAutomationState: String?,
    @ColumnInfo(name = "record_action_at_epoch_ms")
    val recordActionAtEpochMs: Long?,
    @ColumnInfo(name = "recording_verified_at_epoch_ms")
    val recordingVerifiedAtEpochMs: Long?,
    @ColumnInfo(name = "media_baseline_generation")
    val mediaBaselineGeneration: Long?,
    @ColumnInfo(name = "media_store_version")
    val mediaStoreVersion: String?,
    @ColumnInfo(name = "media_verification_required", defaultValue = "1")
    val mediaVerificationRequired: Boolean,
    @ColumnInfo(name = "media_saved_verified_at_epoch_ms")
    val mediaSavedVerifiedAtEpochMs: Long?,
    @ColumnInfo(name = "saved_media_generation")
    val savedMediaGeneration: Long?,
    @ColumnInfo(name = "stop_action_at_epoch_ms")
    val stopActionAtEpochMs: Long?,
    @ColumnInfo(name = "stopped_verified_at_epoch_ms")
    val stoppedVerifiedAtEpochMs: Long?,
    @ColumnInfo(name = "camera_ownership_released_at_epoch_ms")
    val cameraOwnershipReleasedAtEpochMs: Long?,
    @ColumnInfo(name = "environment_snapshot_id")
    val environmentSnapshotId: String?,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
    @ColumnInfo(name = "failure_message")
    val failureMessage: String?,
    @ColumnInfo(name = "failure_context_json")
    val failureContextJson: String?,
    val revision: Long,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "environment_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onUpdate = ForeignKey.RESTRICT,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["session_id"], unique = true)],
)
internal data class EnvironmentSnapshotEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "captured_at_epoch_ms")
    val capturedAtEpochMs: Long,
    @ColumnInfo(name = "lenswake_version")
    val lenswakeVersion: String,
    @ColumnInfo(name = "device_manufacturer")
    val deviceManufacturer: String,
    @ColumnInfo(name = "device_model")
    val deviceModel: String,
    @ColumnInfo(name = "android_sdk")
    val androidSdk: Int,
    @ColumnInfo(name = "android_build_fingerprint")
    val androidBuildFingerprint: String?,
    @ColumnInfo(name = "camera_package")
    val cameraPackage: String,
    @ColumnInfo(name = "camera_version_code")
    val cameraVersionCode: Long,
    @ColumnInfo(name = "locale_tag")
    val localeTag: String,
    @ColumnInfo(name = "display_width_px")
    val displayWidthPx: Int,
    @ColumnInfo(name = "display_height_px")
    val displayHeightPx: Int,
    @ColumnInfo(name = "density_dpi")
    val densityDpi: Int,
    @ColumnInfo(name = "accessibility_status")
    val accessibilityStatus: String,
    @ColumnInfo(name = "privileged_bridge_status")
    val privilegedBridgeStatus: String,
    @ColumnInfo(name = "screen_interactive")
    val screenInteractive: Boolean,
    @ColumnInfo(name = "keyguard_locked")
    val keyguardLocked: Boolean,
    @ColumnInfo(name = "battery_percent")
    val batteryPercent: Int?,
    val charging: Boolean?,
    @ColumnInfo(name = "available_storage_bytes")
    val availableStorageBytes: Long?,
)

@Entity(
    tableName = "execution_events",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onUpdate = ForeignKey.RESTRICT,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["session_id", "sequence"], unique = true),
    ],
)
internal data class ExecutionEventEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    val name: String,
    val sequence: Long,
    @ColumnInfo(name = "timestamp_epoch_ms")
    val timestampEpochMs: Long,
    val state: String,
    val operation: String?,
    val outcome: String,
    @ColumnInfo(name = "interaction_method")
    val interactionMethod: String?,
    val attempt: Int?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,
    @ColumnInfo(name = "failure_code")
    val failureCode: String?,
    @ColumnInfo(name = "failure_message")
    val failureMessage: String?,
    @ColumnInfo(name = "failure_context_json")
    val failureContextJson: String?,
    @ColumnInfo(name = "metadata_json")
    val metadataJson: String,
)
