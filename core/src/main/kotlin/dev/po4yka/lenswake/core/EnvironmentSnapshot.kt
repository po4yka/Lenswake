package dev.po4yka.lenswake.core

import java.time.Instant

private const val MAX_BATTERY_PERCENT = 100

/**
 * Immutable diagnostic context captured immediately before an automation run.
 *
 * Sensitive UI contents are intentionally absent. The snapshot contains only operational state
 * needed to explain why a local execution succeeded or failed.
 */
data class EnvironmentSnapshot(
    val id: EnvironmentSnapshotId,
    val sessionId: SessionId,
    val capturedAt: Instant,
    val lenswakeVersion: String,
    val cameraEnvironment: PixelCameraEnvironment,
    val profileProvenance: ProfileProvenance = LEGACY_PROFILE_PROVENANCE,
    val accessibilityStatus: EnvironmentCapabilityStatus,
    val privilegedBridgeStatus: EnvironmentCapabilityStatus,
    val screenInteractive: Boolean,
    val keyguardLocked: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val availableStorageBytes: Long?,
) {
    init {
        require(lenswakeVersion.isNotBlank()) { "Lenswake version must not be blank" }
        require(batteryPercent == null || batteryPercent in 0..MAX_BATTERY_PERCENT) {
            "Battery percent must be between zero and one hundred"
        }
        require(availableStorageBytes == null || availableStorageBytes >= 0) {
            "Available storage must not be negative"
        }
    }
}

enum class EnvironmentCapabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

sealed interface EnvironmentSnapshotCaptureResult {
    data class Captured(
        val snapshot: EnvironmentSnapshot,
        val session: ExecutionSession,
    ) : EnvironmentSnapshotCaptureResult

    /** The requested insert was rejected because its id or session already owns a snapshot. */
    data class AlreadyExists(
        val existing: EnvironmentSnapshot,
        val session: ExecutionSession,
    ) : EnvironmentSnapshotCaptureResult
}

data class ExecutionReport(
    val session: ExecutionSession,
    val environmentSnapshot: EnvironmentSnapshot?,
    val events: List<AutomationEvent>,
)
