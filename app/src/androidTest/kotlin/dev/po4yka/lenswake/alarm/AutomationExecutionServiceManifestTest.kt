package dev.po4yka.lenswake.alarm

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationExecutionServiceManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun executionServiceIsPrivateAndDeclaresSystemExemptedType() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AutomationExecutionService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertFalse(info.exported)
        assertTrue(
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED != 0,
        )
    }

    @Test
    fun recoveryServiceIsPrivateAndDeclaresSystemExemptedType() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AlarmRecoveryService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertFalse(info.exported)
        assertTrue(
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED != 0,
        )
        assertEquals(android.app.Service.START_REDELIVER_INTENT, ALARM_RECOVERY_RESTART_MODE)
    }

    @Test
    fun manifestDeclaresBothForegroundServicePermissions() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED in permissions)
    }

    @Test
    fun legacyStartAndStopBroadcastReceiversAreNotRegistered() {
        listOf("StartAlarmReceiver", "StopAlarmReceiver").forEach { simpleName ->
            val component = ComponentName(
                context.packageName,
                "${context.packageName}.alarm.$simpleName",
            )
            try {
                context.packageManager.getReceiverInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0),
                )
                fail("Legacy receiver remains registered: $simpleName")
            } catch (_: PackageManager.NameNotFoundException) {
                // Expected: exact alarms now target the foreground service directly.
            }
        }
    }

    @Test
    fun serviceContractUsesRedeliveryForProcessRecreation() {
        assertEquals(android.app.Service.START_REDELIVER_INTENT, AUTOMATION_SERVICE_RESTART_MODE)
    }

}
