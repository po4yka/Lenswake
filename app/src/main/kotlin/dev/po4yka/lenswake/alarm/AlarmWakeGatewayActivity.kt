package dev.po4yka.lenswake.alarm

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Visible, bounded target for a locally posted alarm-category full-screen intent.
 *
 * This Activity deliberately does not dismiss keyguard, hold a wake lock, forward alarm work, or
 * perform automation. Its only valid mode asks Android to turn the display on over keyguard while the
 * already-running durable execution service continues the scheduled workflow.
 */
internal class AlarmWakeGatewayActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val finishAtDeadline = Runnable(::finishGateway)
    private var acceptedWake = false

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
        if (acceptedWake && !isChangingConfigurations) finishGateway()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishAtDeadline)
        super.onDestroy()
    }

    private fun handleIntent(incoming: Intent) {
        handler.removeCallbacks(finishAtDeadline)
        acceptedWake = AlarmWakeGatewayContract.isWakeOnly(this, incoming)
        if (acceptedWake) {
            scheduleDeadline()
        } else {
            finishGateway()
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

}
