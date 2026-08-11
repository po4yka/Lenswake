package dev.po4yka.lenswake.alarm

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.po4yka.lenswake.platform.PendingIntentCreatorBackgroundActivityStartMode

/** Keeps durable automation alarms service-bound while migrating the rejected gateway transport. */
internal object AutomationAlarmPendingIntentFactory {
    fun createOrUpdate(
        context: Context,
        requestCode: Int,
        intent: Intent,
    ): PendingIntent = PendingIntent.getForegroundService(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun find(
        context: Context,
        requestCode: Int,
        identityIntent: Intent,
    ): PendingIntent? = PendingIntent.getForegroundService(
        context,
        requestCode,
        identityIntent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Removes the activity token installed by the superseded wake-gateway alarm transport. */
    fun cancelLegacyGatewayActivityIdentity(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        identityIntent: Intent,
    ): Boolean {
        val legacy = PendingIntent.getActivity(
            context,
            requestCode,
            legacyGatewayActivityIntent(context, identityIntent),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions(),
        ) ?: return false
        alarmManager.cancel(legacy)
        legacy.cancel()
        return true
    }

    /** Preserves an installed activity alarm unless Android accepts its service replacement first. */
    fun armReplacementThenCancelLegacyGatewayActivityIdentities(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        legacyIdentityIntents: List<Intent>,
        armReplacement: () -> Unit,
    ) {
        armReplacement()
        legacyIdentityIntents.forEach { identityIntent ->
            cancelLegacyGatewayActivityIdentity(
                context = context,
                alarmManager = alarmManager,
                requestCode = requestCode,
                identityIntent = identityIntent,
            )
        }
    }

    internal fun legacyGatewayActivityIntent(context: Context, intent: Intent): Intent = Intent(intent)
        .setComponent(ComponentName(context, AlarmWakeGatewayActivity::class.java))
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )

    private fun creatorOptions() = ActivityOptions.makeBasic()
        .setPendingIntentCreatorBackgroundActivityStartMode(
            PendingIntentCreatorBackgroundActivityStartMode.resolve(),
        )
        .toBundle()
}
