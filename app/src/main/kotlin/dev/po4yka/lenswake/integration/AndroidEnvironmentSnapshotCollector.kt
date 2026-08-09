package dev.po4yka.lenswake.integration

import android.app.KeyguardManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
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
import java.util.concurrent.CancellationException

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

    override suspend fun collect(
        snapshotId: EnvironmentSnapshotId,
        sessionId: SessionId,
    ): Result<EnvironmentSnapshot> = try {
        val cameraEnvironment = when (val result = cameraEnvironmentProbe.inspect()) {
            is PortResult.Observed -> result.value
            is PortResult.Unavailable -> error(result.failure.message)
        }
        Result.success(
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
                availableStorageBytes = runCatching { applicationContext.filesDir.usableSpace }
                    .getOrNull()
                    ?.takeIf { it >= 0 },
            ),
        )
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    }

    private suspend fun accessibilityStatus(): EnvironmentCapabilityStatus = try {
        when (PixelCameraAccessibilityRuntime.snapshot()) {
            AccessibilitySnapshotResult.ServiceDisconnected -> EnvironmentCapabilityStatus.UNAVAILABLE
            is AccessibilitySnapshotResult.Available,
            AccessibilitySnapshotResult.NoActiveWindow,
            AccessibilitySnapshotResult.PixelCameraNotForeground,
            -> EnvironmentCapabilityStatus.AVAILABLE
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        EnvironmentCapabilityStatus.UNKNOWN
    }

    private suspend fun privilegedStatus(): EnvironmentCapabilityStatus = try {
        when (privilegedBridge.availability()) {
            PrivilegedResult.Dispatched -> EnvironmentCapabilityStatus.AVAILABLE
            is PrivilegedResult.Unavailable -> EnvironmentCapabilityStatus.UNAVAILABLE
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        EnvironmentCapabilityStatus.UNKNOWN
    }

    private fun batteryPercent(): Int? = runCatching {
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()?.takeIf { it in 0..100 }
}
