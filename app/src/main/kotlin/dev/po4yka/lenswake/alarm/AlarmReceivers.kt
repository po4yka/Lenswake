package dev.po4yka.lenswake.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class AutomationAlarmReceiver(
    private val kind: AlarmKind,
) : BroadcastReceiver() {
    final override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("lenswake-${kind.name.lowercase()}-alarm"),
        )
        scope.launch {
            try {
                val trigger = AlarmContract.parse(intent, kind)
                if (trigger == null) {
                    Log.e(TAG, "Rejected malformed ${kind.name} alarm")
                    return@launch
                }
                val provider = context.applicationContext as? AlarmComponentProvider
                if (provider == null) {
                    Log.e(TAG, "Application does not provide AlarmComponentProvider")
                    return@launch
                }
                when (val result = provider.alarmTriggerCoordinator.handle(trigger)) {
                    AlarmHandlingResult.Accepted -> Log.i(
                        TAG,
                        "${kind.name} alarm durably accepted for ${trigger.scheduleId.value}",
                    )
                    is AlarmHandlingResult.Rejected -> Log.e(
                        TAG,
                        "${kind.name} alarm rejected for ${trigger.scheduleId.value}: ${result.reason}",
                        result.cause,
                    )
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unhandled ${kind.name} alarm failure", error)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "LenswakeAlarm"
    }
}

class StartAlarmReceiver : AutomationAlarmReceiver(AlarmKind.START)

class StopAlarmReceiver : AutomationAlarmReceiver(AlarmKind.STOP)

class AlarmRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val pendingResult = goAsync()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("lenswake-alarm-recovery"),
        )
        scope.launch {
            try {
                val provider = context.applicationContext as? AlarmComponentProvider
                if (provider == null) {
                    Log.e(TAG, "Application does not provide AlarmComponentProvider")
                    return@launch
                }
                provider.alarmRecoveryCoordinator.restoreFutureSchedules()
                    .onFailure { Log.e(TAG, "Future alarm restoration failed", it) }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unhandled future alarm restoration failure", error)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "LenswakeAlarm"
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
