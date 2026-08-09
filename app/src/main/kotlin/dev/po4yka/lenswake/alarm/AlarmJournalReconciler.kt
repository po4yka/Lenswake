package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.content.Context

internal sealed interface JournalRearmResult {
    data class Rearmed(val count: Int) : JournalRearmResult
    data object ExactAlarmsUnavailable : JournalRearmResult
    data class Failed(val cause: Throwable) : JournalRearmResult
}

internal interface ExactAlarmRearmBackend {
    fun canScheduleExactAlarms(): Boolean
    fun rearm(work: AlarmDeliveryWork, triggerAtEpochMillis: Long)
}

internal class AndroidExactAlarmRearmBackend(
    private val context: Context,
) : ExactAlarmRearmBackend {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExactAlarms(): Boolean = alarmManager.canScheduleExactAlarms()

    override fun rearm(work: AlarmDeliveryWork, triggerAtEpochMillis: Long) {
        val pendingIntent = AlarmWakePendingIntentFactory.createOrUpdate(
            context,
            AlarmDeliveryWorkContract.requestCode(work),
            AlarmDeliveryWorkContract.triggerIntent(context, work),
        )
        AlarmWakePendingIntentFactory.armReplacementThenCancelLegacyServiceIdentities(
            context = context,
            alarmManager = alarmManager,
            requestCode = AlarmDeliveryWorkContract.requestCode(work),
            legacyIdentityIntents = listOf(
                AlarmDeliveryWorkContract.deliveryIdentityIntent(context, work),
            ),
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMillis,
                pendingIntent,
            )
        }
    }
}

/** Re-arms journal transport only; it never calls the camera or automation coordinator. */
internal class AlarmJournalReconciler(
    private val journal: AlarmDeliveryJournal,
    private val backend: ExactAlarmRearmBackend,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun rearmAll(): JournalRearmResult {
        val entries = journal.entries()
        if (entries.isEmpty()) return JournalRearmResult.Rearmed(0)
        if (!backend.canScheduleExactAlarms()) return JournalRearmResult.ExactAlarmsUnavailable
        return try {
            val firstTriggerAt = nowEpochMillis() + REARM_DELAY_MILLIS
            entries.forEachIndexed { index, entry ->
                backend.rearm(entry.work, firstTriggerAt + index * REARM_STAGGER_MILLIS)
            }
            JournalRearmResult.Rearmed(entries.size)
        } catch (error: RuntimeException) {
            JournalRearmResult.Failed(error)
        }
    }

    private companion object {
        const val REARM_DELAY_MILLIS = 30_000L
        const val REARM_STAGGER_MILLIS = 1_000L
    }
}
