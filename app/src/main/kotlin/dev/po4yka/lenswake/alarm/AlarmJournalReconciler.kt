package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.content.Context

internal sealed interface JournalRearmResult {
    val corruptEntries: List<AlarmDeliveryJournal.CorruptEntry>

    data class Rearmed(
        val count: Int,
        override val corruptEntries: List<AlarmDeliveryJournal.CorruptEntry> = emptyList(),
    ) : JournalRearmResult

    data class ExactAlarmsUnavailable(
        override val corruptEntries: List<AlarmDeliveryJournal.CorruptEntry>,
    ) : JournalRearmResult

    data class Failed(
        val cause: Throwable,
        override val corruptEntries: List<AlarmDeliveryJournal.CorruptEntry>,
    ) : JournalRearmResult
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
        val pendingIntent = AutomationAlarmPendingIntentFactory.createOrUpdate(
            context,
            AlarmDeliveryWorkContract.requestCode(work),
            AlarmDeliveryWorkContract.triggerIntent(context, work),
        )
        AutomationAlarmPendingIntentFactory.armReplacementThenCancelLegacyGatewayActivityIdentities(
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

/**
 * Re-arms journal transport only; it never calls the camera or automation coordinator. The exact
 * alarm backend crosses several Android APIs that can fail with unrelated runtime exception types;
 * all represent the same retained-journal recovery failure.
 */
internal class AlarmJournalReconciler(
    private val journal: AlarmDeliveryJournal,
    private val backend: ExactAlarmRearmBackend,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    @Suppress("TooGenericExceptionCaught")
    fun rearmAll(): JournalRearmResult {
        val snapshot = journal.read()
        val entries = snapshot.entries
        return when {
            entries.isEmpty() -> JournalRearmResult.Rearmed(0, snapshot.corruptEntries)
            !backend.canScheduleExactAlarms() ->
                JournalRearmResult.ExactAlarmsUnavailable(snapshot.corruptEntries)
            else -> try {
                val firstTriggerAt = nowEpochMillis() + REARM_DELAY_MILLIS
                entries.forEachIndexed { index, entry ->
                    backend.rearm(entry.work, firstTriggerAt + index * REARM_STAGGER_MILLIS)
                }
                    JournalRearmResult.Rearmed(entries.size, snapshot.corruptEntries)
            } catch (error: RuntimeException) {
                JournalRearmResult.Failed(error, snapshot.corruptEntries)
            }
        }
    }

    private companion object {
        const val REARM_DELAY_MILLIS = 30_000L
        const val REARM_STAGGER_MILLIS = 1_000L
    }
}
