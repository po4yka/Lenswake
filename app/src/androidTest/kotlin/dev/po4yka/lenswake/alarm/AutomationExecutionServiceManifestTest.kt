package dev.po4yka.lenswake.alarm

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
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
    fun recoveryServiceIsPrivateJobServiceIndependentOfExactAlarmAccess() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, AlarmRecoveryService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertFalse(info.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertEquals(0, info.foregroundServiceType)
    }

    @Test
    fun recoveryJobIsPersistedAndExpedited() {
        val job = AndroidAlarmRecoveryJobScheduler(context)
            .jobInfo(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertTrue(job.isPersisted)
        assertTrue(job.isExpedited)
        assertEquals(Intent.ACTION_MY_PACKAGE_REPLACED, job.extras.getString(EXTRA_RECOVERY_ACTION))
    }

    @Test
    fun recoveryRetryIsPersistedWithoutExactAlarmTransport() {
        val job = AndroidAlarmRecoveryJobScheduler(context)
            .retryJobInfo(System.currentTimeMillis() + 30_000L)

        assertTrue(job.isPersisted)
        assertFalse(job.isExpedited)
        assertTrue(job.minLatencyMillis > 0L)
        assertEquals(ACTION_ALARM_RECOVERY_RETRY, job.extras.getString(EXTRA_RECOVERY_ACTION))
    }

    @Test
    fun manifestDeclaresForegroundServiceAndNotificationPermissions() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
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

    @Test
    fun recoveryServiceRunsAndPersistsCapabilityFailureWithoutExactAlarmAccess() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        assumeFalse(alarmManager.canScheduleExactAlarms())
        val checkpointPersistence = SharedPreferencesAlarmRecoveryCheckpointPersistence(context)
        checkpointPersistence.clear()
        val processId = Process.myPid()

        AndroidAlarmRecoveryJobScheduler(context)
            .schedule(Intent.ACTION_MY_PACKAGE_REPLACED)
            .getOrThrow()
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (
            checkpointPersistence.checkpoint()?.exhausted != true &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(100L)
        }

        assertEquals(processId, Process.myPid())
        val checkpoint = checkpointPersistence.checkpoint()
        assertTrue(checkpoint?.exhausted == true)
        assertTrue(checkpoint?.lastFailure?.contains("Exact alarm access is unavailable") == true)
    }

    @Test
    fun bootRecoveryReceiverRemainsSafeWithoutExactAlarmAccess() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        assumeFalse(alarmManager.canScheduleExactAlarms())
        val processId = Process.myPid()

        AlarmRecoveryReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        SystemClock.sleep(1_000L)

        assertEquals(processId, Process.myPid())
    }

}
