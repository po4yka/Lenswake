package dev.po4yka.lenswake.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmJournalReconcilerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun exactAlarmDenialRetainsEveryJournaledTrigger() {
        withJournal { journal ->
            val backend = FakeRearmBackend(canSchedule = false)

            val result = AlarmJournalReconciler(journal, backend).rearmAll()

            assertTrue(result is JournalRearmResult.ExactAlarmsUnavailable)
            assertEquals(2, journal.entries().size)
            assertTrue(backend.rearmed.isEmpty())
        }
    }

    @Test
    fun recoveryRearmsAllEntriesWithoutConsumingDomainWork() {
        withJournal { journal ->
            val backend = FakeRearmBackend(canSchedule = true)

            val result = AlarmJournalReconciler(
                journal = journal,
                backend = backend,
                nowEpochMillis = { 1_000L },
            ).rearmAll()

            assertEquals(JournalRearmResult.Rearmed(2), result)
            assertEquals(setOf(AlarmKind.START, AlarmKind.STOP), backend.rearmed.map { it.first.kind }.toSet())
            assertEquals(2, journal.entries().size)
        }
    }

    private fun withJournal(test: (AlarmDeliveryJournal) -> Unit) {
        val journal = AlarmDeliveryJournal(context, "rearm-test-${System.nanoTime()}")
        val schedule = testSchedule()
        requireNotNull(journal.persist(AlarmContract.intent(context, schedule, AlarmKind.START)))
        requireNotNull(journal.persist(AlarmContract.intent(context, schedule, AlarmKind.STOP)))
        try {
            test(journal)
        } finally {
            journal.entries().forEach { journal.remove(it.key) }
        }
    }
}

private class FakeRearmBackend(
    private val canSchedule: Boolean,
) : ExactAlarmRearmBackend {
    val rearmed = mutableListOf<Pair<AlarmTrigger, Long>>()

    override fun canScheduleExactAlarms(): Boolean = canSchedule

    override fun rearm(trigger: AlarmTrigger, triggerAtEpochMillis: Long) {
        rearmed += trigger to triggerAtEpochMillis
    }
}
