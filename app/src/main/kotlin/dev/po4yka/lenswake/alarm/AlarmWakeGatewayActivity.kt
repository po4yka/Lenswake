package dev.po4yka.lenswake.alarm

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * Visible, bounded bridge from a wakeup alarm to durable foreground-service execution.
 *
 * This Activity deliberately does not dismiss keyguard, hold a wake lock, or perform automation. Its
 * window asks Android to turn the display on and be shown over keyguard. A structurally valid explicit
 * alarm is immediately forwarded to [AutomationExecutionService], which persists and revalidates it.
 */
internal class AlarmWakeGatewayActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val finishAtDeadline = Runnable(::finishGateway)
    private var acceptedMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (acceptedMode && !isChangingConfigurations) finishGateway()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishAtDeadline)
        super.onDestroy()
    }

    private fun handleIntent(incoming: Intent) {
        handler.removeCallbacks(finishAtDeadline)
        acceptedMode = false

        when {
            AlarmWakeGatewayContract.isWakeOnly(this, incoming) -> {
                acceptedMode = true
                scheduleDeadline()
            }

            else -> {
                val serviceIntent = AlarmWakeGatewayContract.forwardedServiceIntent(
                    context = this,
                    incoming = incoming,
                )
                if (serviceIntent == null) {
                    Log.e(TAG, "Rejected malformed or non-explicit wake-gateway intent")
                    finishGateway()
                    return
                }
                try {
                    startForegroundService(serviceIntent)
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Could not forward durable alarm to execution service", error)
                    finishGateway()
                    return
                }
                acceptedMode = true
                scheduleDeadline()
            }
        }
    }

    private fun scheduleDeadline() {
        handler.postDelayed(finishAtDeadline, MAX_VISIBLE_MILLIS)
    }

    private fun finishGateway() {
        handler.removeCallbacks(finishAtDeadline)
        if (!isFinishing) finish()
    }

    private companion object {
        const val TAG = "LenswakeWakeGateway"
        const val MAX_VISIBLE_MILLIS = 20_000L
    }
}

internal object AlarmWakeGatewayContract {
    private const val ACTION_WAKE_ONLY = "dev.po4yka.lenswake.action.WAKE_ONLY"

    fun component(context: Context): ComponentName =
        ComponentName(context, AlarmWakeGatewayActivity::class.java)

    fun wakeOnlyIntent(context: Context): Intent = Intent(ACTION_WAKE_ONLY)
        .setComponent(component(context))
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_HISTORY or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )

    fun isWakeOnly(context: Context, intent: Intent): Boolean =
        intent.component == component(context) &&
            intent.action == ACTION_WAKE_ONLY &&
            intent.data == null &&
            intent.extras?.isEmpty != false

    fun forwardedServiceIntent(context: Context, incoming: Intent): Intent? {
        if (incoming.component != component(context)) return null
        if (AlarmDeliveryWorkContract.parse(incoming) == null) return null
        return Intent(incoming)
            .setComponent(ComponentName(context, AutomationExecutionService::class.java))
            .setFlags(0)
    }
}
