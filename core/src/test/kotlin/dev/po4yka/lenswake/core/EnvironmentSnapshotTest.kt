package dev.po4yka.lenswake.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EnvironmentSnapshotTest {
    @Test
    fun rejectsInvalidBatteryAndStorageValues() {
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(batteryPercent = 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(availableStorageBytes = -1)
        }
    }

    private fun snapshot(
        batteryPercent: Int? = 50,
        availableStorageBytes: Long? = 1_024,
    ): EnvironmentSnapshot = EnvironmentSnapshot(
        id = EnvironmentSnapshotId("snapshot-1"),
        sessionId = SessionId("session-1"),
        capturedAt = Instant.EPOCH,
        lenswakeVersion = "1.0",
        cameraEnvironment = PixelCameraEnvironment(
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            androidSdk = 37,
            androidBuildFingerprint = "google/husky/test",
            cameraPackage = "com.google.android.GoogleCamera",
            cameraVersionCode = 1,
            localeTag = "en-US",
            displayWidthPx = 1_344,
            displayHeightPx = 2_992,
            densityDpi = 480,
        ),
        accessibilityStatus = EnvironmentCapabilityStatus.AVAILABLE,
        privilegedBridgeStatus = EnvironmentCapabilityStatus.UNKNOWN,
        screenInteractive = false,
        keyguardLocked = true,
        batteryPercent = batteryPercent,
        charging = null,
        availableStorageBytes = availableStorageBytes,
    )
}
