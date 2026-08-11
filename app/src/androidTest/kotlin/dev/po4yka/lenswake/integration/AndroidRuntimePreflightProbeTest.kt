package dev.po4yka.lenswake.integration

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.StatFs
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.LenswakeApplication
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.platform.DeviceWakeController
import dev.po4yka.lenswake.platform.PlatformCapability
import dev.po4yka.lenswake.platform.PlatformCapabilityCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@RunWith(AndroidJUnit4::class)
class AndroidRuntimePreflightProbeTest {
    @Test
    fun connectionInvalidationEmitsCurrentStateToLateCollectors() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<LenswakeApplication>()
        val probe = AndroidRuntimePreflightProbe(
            context = application,
            cameraEnvironmentProbe = AndroidPixelCameraEnvironmentProbe(application),
            executionRepository = application.graph.executionRepository,
        )

        withTimeout(1_000) {
            probe.invalidations.first()
        }
    }

    @Test
    fun reportsObservedTargetCapabilitiesWithoutGrantingAccess() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<LenswakeApplication>()
        val report = AndroidRuntimePreflightProbe(
            context = application,
            cameraEnvironmentProbe = AndroidPixelCameraEnvironmentProbe(application),
            executionRepository = application.graph.executionRepository,
        ).inspect(emptyList())

        val checks = report.checks.associateBy { it.type }
        val alarmManager = application.getSystemService(AlarmManager::class.java)
        assertEquals(
            if (alarmManager.canScheduleExactAlarms()) PreflightStatus.PASSED else PreflightStatus.FAILED,
            checks.getValue(PreflightCheckType.EXACT_ALARMS).status,
        )
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        val notificationPermission = application.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        assertEquals(
            if (notificationPermission && notificationManager.areNotificationsEnabled()) {
                PreflightStatus.PASSED
            } else {
                PreflightStatus.FAILED
            },
            checks.getValue(PreflightCheckType.NOTIFICATIONS).status,
        )
        val mediaVideoPermission = application.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
        assertEquals(
            if (mediaVideoPermission) PreflightStatus.PASSED else PreflightStatus.FAILED,
            checks.getValue(PreflightCheckType.MEDIA_VIDEO_ACCESS).status,
        )
        assertEquals(
            if (notificationManager.canUseFullScreenIntent()) PreflightStatus.PASSED else PreflightStatus.FAILED,
            checks.getValue(PreflightCheckType.FULL_SCREEN_INTENT).status,
        )
        assertEquals(
            PreflightStatus.PASSED,
            checks.getValue(PreflightCheckType.PIXEL_CAMERA_INSTALLED).status,
        )
        assertEquals(
            PreflightStatus.PASSED,
            checks.getValue(PreflightCheckType.SECURE_CAMERA_RESOLVES).status,
        )
        assertEquals(
            PreflightStatus.FAILED,
            checks.getValue(PreflightCheckType.PROFILE_AVAILABLE).status,
        )
        assertTrue(checks.containsKey(PreflightCheckType.ACCESSIBILITY_ENABLED))
        assertTrue(checks.containsKey(PreflightCheckType.ACCESSIBILITY_CONNECTED))
        assertEquals(PreflightStatus.PASSED, checks.getValue(PreflightCheckType.DEVICE_WAKE).status)
        val batteryManager = application.getSystemService(BatteryManager::class.java)
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        assertEquals(
            if (batteryPercent in MINIMUM_BATTERY_PERCENT..100) {
                PreflightStatus.PASSED
            } else if (batteryPercent in 0 until MINIMUM_BATTERY_PERCENT) {
                PreflightStatus.FAILED
            } else {
                PreflightStatus.UNKNOWN
            },
            checks.getValue(PreflightCheckType.BATTERY).status,
        )
        assertEquals(
            when (batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
                -> PreflightStatus.PASSED
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                -> PreflightStatus.FAILED
                else -> PreflightStatus.UNKNOWN
            },
            checks.getValue(PreflightCheckType.CHARGING).status,
        )
        val primarySharedStorage = application.getExternalFilesDir(null)
        if (primarySharedStorage != null) {
            val available = StatFs(primarySharedStorage.absolutePath).availableBytes
            assertEquals(
                if (available >= MINIMUM_AVAILABLE_STORAGE_BYTES) {
                    PreflightStatus.PASSED
                } else {
                    PreflightStatus.FAILED
                },
                checks.getValue(PreflightCheckType.STORAGE).status,
            )
            assertTrue(available >= 0)
        } else {
            assertEquals(
                PreflightStatus.UNKNOWN,
                checks.getValue(PreflightCheckType.STORAGE).status,
            )
        }
        assertTrue(report.readiness is dev.po4yka.lenswake.core.ScheduleReadiness.Blocked)
    }

    @Test
    fun reportsWakeGatewayCapabilityFailureWithoutDispatchingIt() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<LenswakeApplication>()
        var wakeDispatches = 0
        val controller = object : DeviceWakeController {
            override fun availability(): PlatformCapability<Unit> = PlatformCapability.Unavailable(
                code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
                detail = "Wake gateway is disabled for this test.",
            )

            override suspend fun wakeDevice(): PlatformCapability<Unit> {
                wakeDispatches += 1
                return PlatformCapability.Available(Unit)
            }
        }
        val report = AndroidRuntimePreflightProbe(
            context = application,
            cameraEnvironmentProbe = AndroidPixelCameraEnvironmentProbe(application),
            executionRepository = application.graph.executionRepository,
            deviceWakeController = controller,
        ).inspect(emptyList())

        val wake = report.checks.single { it.type == PreflightCheckType.DEVICE_WAKE }
        assertEquals(PreflightStatus.FAILED, wake.status)
        assertEquals("Wake gateway is disabled for this test.", wake.message)
        assertEquals(0, wakeDispatches)
    }
}
