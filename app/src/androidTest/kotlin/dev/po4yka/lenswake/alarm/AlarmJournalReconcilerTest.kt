package dev.po4yka.lenswake.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        withJournal { journal ->
            val backend = FakeRearmBackend(canSchedule = false)

            val result = AlarmJournalReconciler(journal, backend).rearmAll()

            assertTrue(result is JournalRearmResult.ExactAlarmsUnavailable)
            assertEquals(3, journal.entries().size)
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

            assertEquals(JournalRearmResult.Rearmed(3), result)
            assertEquals(
                setOf(AlarmKind.START, AlarmKind.STOP),
                backend.rearmed.mapNotNull { (it.first as? AlarmDeliveryWork.Schedule)?.trigger?.kind }.toSet(),
            )
            assertTrue(backend.rearmed.any { it.first is AlarmDeliveryWork.RehearsalStop })
            assertEquals(3, journal.entries().size)
        }
    }

    private fun withJournal(test: (AlarmDeliveryJournal) -> Unit) {
        val journal = AlarmDeliveryJournal(context, "rearm-test-${System.nanoTime()}")
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
            test(journal)
        } finally {
            journal.entries().forEach { journal.remove(it.key) }
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
