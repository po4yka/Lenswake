package dev.po4yka.lenswake.integration

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.LenswakeApplication
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRuntimePreflightProbeTest {
    @Test
    fun reportsObservedTargetCapabilitiesWithoutGrantingAccess() {
        val application = ApplicationProvider.getApplicationContext<LenswakeApplication>()
        val report = AndroidRuntimePreflightProbe(
            context = application,
            cameraEnvironmentProbe = AndroidPixelCameraEnvironmentProbe(application),
        ).inspect(emptyList())

        val checks = report.checks.associateBy { it.type }
        val alarmManager = application.getSystemService(AlarmManager::class.java)
        assertEquals(
            if (alarmManager.canScheduleExactAlarms()) PreflightStatus.PASSED else PreflightStatus.FAILED,
            checks.getValue(PreflightCheckType.EXACT_ALARMS).status,
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
        assertTrue(report.readiness is dev.po4yka.lenswake.core.ScheduleReadiness.Blocked)
    }
}
