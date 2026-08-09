package dev.po4yka.lenswake.alarm

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Keeps every automation alarm on the same activity PendingIntent identity and BAL policy. */
internal object AlarmWakePendingIntentFactory {
    fun createOrUpdate(
        context: Context,
        requestCode: Int,
        intent: Intent,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        creatorOptions(),
    )

    fun find(
        context: Context,
        requestCode: Int,
        identityIntent: Intent,
    ): PendingIntent? = PendingIntent.getActivity(
        context,
        requestCode,
        identityIntent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        creatorOptions(),
    )

    /** Removes the service token created by Lenswake versions before the wake gateway migration. */
    fun cancelLegacyServiceIdentity(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        identityIntent: Intent,
    ): Boolean {
        val legacy = PendingIntent.getForegroundService(
            context,
            requestCode,
            legacyServiceIntent(context, identityIntent),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return false
        alarmManager.cancel(legacy)
        legacy.cancel()
        return true
    }

    /** Preserves the legacy alarm unless Android first accepts its activity-based replacement. */
    fun armReplacementThenCancelLegacyServiceIdentities(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        legacyIdentityIntents: List<Intent>,
        armReplacement: () -> Unit,
    ) {
        armReplacement()
        legacyIdentityIntents.forEach { identityIntent ->
            cancelLegacyServiceIdentity(
                context = context,
                alarmManager = alarmManager,
                requestCode = requestCode,
                identityIntent = identityIntent,
            )
        }
    }

    internal fun legacyServiceIntent(context: Context, intent: Intent): Intent = Intent(intent)
        .setComponent(ComponentName(context, AutomationExecutionService::class.java))
        .setFlags(0)

    private fun creatorOptions() = ActivityOptions.makeBasic()
        .setPendingIntentCreatorBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
        )
        .toBundle()
}

internal object AlarmWakeIntentRouting {
    fun route(context: Context, intent: Intent): Intent = intent
        .setComponent(AlarmWakeGatewayContract.component(context))
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
}
