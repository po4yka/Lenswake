package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmRecoveryCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun recoveryCoordinatorDelegatesOnlyToAlarmRestoration() = runBlocking {
        val scheduler = RecordingSchedulerSpy()
        val coordinator = SchedulerAlarmRecoveryCoordinator(scheduler)

        assertTrue(coordinator.restoreFutureSchedules().isSuccess)
        assertEquals(1, scheduler.restoreCalls)
        assertEquals(0, scheduler.scheduleCalls)
    }

    @Test
    fun recoveryReceiverIsRegisteredForBootAndTimeChanges() {
        val receiver = ComponentName(context, AlarmRecoveryReceiver::class.java)
        val receiverInfo = context.packageManager.getReceiverInfo(
            receiver,
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertEquals(context.packageName, receiverInfo.packageName)
        assertTrue(receiverInfo.enabled)
        listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        ).forEach { action ->
            assertTrue("Recovery receiver does not resolve $action", resolvesToRecoveryReceiver(action))
        }
    }

    private fun resolvesToRecoveryReceiver(action: String): Boolean = context.packageManager
        .queryBroadcastReceivers(
            Intent(action).setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
        .any { it.activityInfo.name == AlarmRecoveryReceiver::class.java.name }
}

private class RecordingSchedulerSpy : RecordingScheduler {
    var restoreCalls = 0
    var scheduleCalls = 0

    override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> {
        scheduleCalls += 1
        return Result.success(Unit)
    }

    override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> {
        scheduleCalls += 1
        return Result.success(Unit)
    }

    override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = Result.success(Unit)

    override suspend fun restoreAll(): Result<Unit> {
        restoreCalls += 1
        return Result.success(Unit)
    }
}
