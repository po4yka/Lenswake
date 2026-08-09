package dev.po4yka.lenswake.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        try {
            context.startForegroundService(
                Intent(context, AlarmRecoveryService::class.java).setAction(intent.action),
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not start restartable alarm recovery service", error)
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
