package dev.po4yka.lenswake.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmJournalReconcilerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun exactAlarmDenialRetainsEveryJournaledTrigger() {
        withJournal { journal, preferenceName ->
            val backend = FakeRearmBackend(canSchedule = false)
            context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                .edit()
                .putLong("corrupt-entry", 42L)
                .commit()

            val result = AlarmJournalReconciler(journal, backend).rearmAll()

            assertTrue(result is JournalRearmResult.ExactAlarmsUnavailable)
            assertEquals(
                listOf("corrupt-entry"),
                result.corruptEntries.map(AlarmDeliveryJournal.CorruptEntry::key),
            )
            assertEquals(3, journal.read().entries.size)
            assertTrue(backend.rearmed.isEmpty())
        }
    }

    @Test
    fun recoveryRearmsAllEntriesWithoutConsumingDomainWork() {
        withJournal { journal, _ ->
            val backend = FakeRearmBackend(canSchedule = true)

            val result = AlarmJournalReconciler(
                journal = journal,
                backend = backend,
                nowEpochMillis = { 1_000L },
            ).rearmAll()

            assertEquals(JournalRearmResult.Rearmed(3), result)
            assertEquals(
                setOf(AlarmKind.START, AlarmKind.STOP),
                backend.rearmed.mapNotNull { (it.first as? AlarmDeliveryWork.Schedule)?.trigger?.kind }.toSet(),
            )
            assertTrue(backend.rearmed.any { it.first is AlarmDeliveryWork.RehearsalStop })
            assertEquals(3, journal.read().entries.size)
        }
    }

    @Test
    fun exhaustedDeliveriesAreNotRearmedDuringRecovery() {
        withJournal { journal, _ ->
            // The exhausted delivery needs its own schedule identity: the journal keeps one
            // winner per markerId, and markerId deliberately excludes the delivery attempt.
            val exhaustedSchedule = testSchedule().copy(id = ScheduleId("alarm-rearm-exhausted"))
            requireNotNull(
                journal.persist(
                    AlarmContract.triggerIntent(
                        context,
                        AlarmTrigger(
                            kind = AlarmKind.STOP,
                            scheduleId = exhaustedSchedule.id,
                            scheduleUpdatedAt = exhaustedSchedule.updatedAt,
                            expectedAt = exhaustedSchedule.stopAt,
                            deliveryAttempt = MAX_ALARM_DELIVERY_ATTEMPTS,
                        ),
                    ),
                ),
            )
            val backend = FakeRearmBackend(canSchedule = true)

            val result = AlarmJournalReconciler(
                journal = journal,
                backend = backend,
                nowEpochMillis = { 1_000L },
            ).rearmAll()

            assertEquals(JournalRearmResult.Rearmed(3), result)
            assertTrue(
                backend.rearmed.none { (work, _) ->
                    work is AlarmDeliveryWork.Schedule &&
                        work.trigger.deliveryAttempt >= MAX_ALARM_DELIVERY_ATTEMPTS
                },
            )
            assertEquals(4, journal.read().entries.size)
        }
    }

    private fun withJournal(test: (AlarmDeliveryJournal, String) -> Unit) {
        val preferenceName = "rearm-test-${System.nanoTime()}"
        val journal = AlarmDeliveryJournal(context, preferenceName)
        val schedule = testSchedule()
        requireNotNull(journal.persist(AlarmContract.intent(context, schedule, AlarmKind.START)))
        requireNotNull(journal.persist(AlarmContract.intent(context, schedule, AlarmKind.STOP)))
        requireNotNull(
            journal.persist(
                RehearsalStopAlarmContract.triggerIntent(
                    context,
                    RehearsalStopTrigger(
                        sessionId = SessionId("rearm-rehearsal"),
                        expectedAt = Instant.parse("2026-08-10T08:00:00Z"),
                    ),
                ),
            ),
        )
        try {
            test(journal, preferenceName)
        } finally {
            context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}

private class FakeRearmBackend(
    private val canSchedule: Boolean,
) : ExactAlarmRearmBackend {
    val rearmed = mutableListOf<Pair<AlarmDeliveryWork, Long>>()

    override fun canScheduleExactAlarms(): Boolean = canSchedule

    override fun rearm(work: AlarmDeliveryWork, triggerAtEpochMillis: Long) {
        rearmed += work to triggerAtEpochMillis
    }
}
