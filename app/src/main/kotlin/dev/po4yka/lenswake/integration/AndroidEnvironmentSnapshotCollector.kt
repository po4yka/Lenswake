package dev.po4yka.lenswake.integration

import android.app.KeyguardManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.storage.StorageManager
import dev.po4yka.lenswake.BuildConfig
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityRuntime
import dev.po4yka.lenswake.application.EnvironmentSnapshotCollector
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.EnvironmentCapabilityStatus
import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.privileged.PrivilegedBridge
import dev.po4yka.lenswake.privileged.PrivilegedResult

/** Android implementation that persists operational facts only, never Accessibility node data. */
class AndroidEnvironmentSnapshotCollector(
    context: Context,
    private val cameraEnvironmentProbe: AndroidPixelCameraEnvironmentProbe,
    private val privilegedBridge: PrivilegedBridge,
    private val clock: LenswakeClock,
) : EnvironmentSnapshotCollector {
    private val applicationContext = context.applicationContext
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private val keyguardManager = applicationContext.getSystemService(KeyguardManager::class.java)
    private val batteryManager = applicationContext.getSystemService(BatteryManager::class.java)
    private val storageManager = applicationContext.getSystemService(StorageManager::class.java)

    override suspend fun collect(
        snapshotId: EnvironmentSnapshotId,
        sessionId: SessionId,
    ): Result<EnvironmentSnapshot> = runSuspendCatchingPreservingCancellation {
        val cameraEnvironment = when (val result = cameraEnvironmentProbe.inspect()) {
            is PortResult.Observed -> result.value
            is PortResult.Unavailable -> error(result.failure.message)
        }
        EnvironmentSnapshot(
            id = snapshotId,
            sessionId = sessionId,
            capturedAt = clock.now(),
            lenswakeVersion = BuildConfig.VERSION_NAME,
            cameraEnvironment = cameraEnvironment,
            accessibilityStatus = accessibilityStatus(),
            privilegedBridgeStatus = privilegedStatus(),
            screenInteractive = powerManager.isInteractive,
            keyguardLocked = keyguardManager.isKeyguardLocked,
            batteryPercent = batteryPercent(),
            charging = runCatching { batteryManager.isCharging }.getOrNull(),
            availableStorageBytes = runCatching {
                storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
            }
                .getOrNull()
                ?.takeIf { it >= 0 },
        )
    }

    private suspend fun accessibilityStatus(): EnvironmentCapabilityStatus =
        runSuspendCatchingPreservingCancellation {
            when (PixelCameraAccessibilityRuntime.snapshot()) {
                AccessibilitySnapshotResult.ServiceDisconnected ->
                    EnvironmentCapabilityStatus.UNAVAILABLE
                AccessibilitySnapshotResult.RefreshFailed -> EnvironmentCapabilityStatus.UNKNOWN
                is AccessibilitySnapshotResult.Available,
                AccessibilitySnapshotResult.NoActiveWindow,
                AccessibilitySnapshotResult.PixelCameraNotForeground,
                -> EnvironmentCapabilityStatus.AVAILABLE
            }
        }.getOrDefault(EnvironmentCapabilityStatus.UNKNOWN)

    private suspend fun privilegedStatus(): EnvironmentCapabilityStatus =
        runSuspendCatchingPreservingCancellation {
            when (privilegedBridge.availability()) {
                PrivilegedResult.Dispatched -> EnvironmentCapabilityStatus.AVAILABLE
                is PrivilegedResult.Unavailable -> EnvironmentCapabilityStatus.UNAVAILABLE
            }
        }.getOrDefault(EnvironmentCapabilityStatus.UNKNOWN)

    private fun batteryPercent(): Int? = runCatching {
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()?.takeIf { it in MINIMUM_PERCENT..MAXIMUM_PERCENT }

    private companion object {
        const val MINIMUM_PERCENT = 0
        const val MAXIMUM_PERCENT = 100
    }
}
