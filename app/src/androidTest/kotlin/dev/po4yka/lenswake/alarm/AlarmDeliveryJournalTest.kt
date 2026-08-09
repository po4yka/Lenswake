package dev.po4yka.lenswake.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.core.SessionId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmDeliveryJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun allQueuedTriggersSurviveJournalRecreationAndReturnToContractParser() {
        val preferenceName = "alarm-journal-test-${System.nanoTime()}"
        val firstJournal = AlarmDeliveryJournal(context, preferenceName)
        val schedule = testSchedule()
        val start = firstJournal.persist(
            AlarmContract.intent(context, schedule, AlarmKind.START),
        )
        val stop = firstJournal.persist(
            AlarmContract.triggerIntent(
                context,
                AlarmTrigger(
                    kind = AlarmKind.STOP,
                    scheduleId = schedule.id,
                    scheduleUpdatedAt = schedule.updatedAt,
                    expectedAt = schedule.stopAt,
                    deliveryAttempt = 1,
                ),
            ),
        )
        assertNotNull(start)
        assertNotNull(stop)

        val restored = AlarmDeliveryJournal(context, preferenceName).entries()

        assertEquals(setOf(AlarmKind.START, AlarmKind.STOP), restored.map { it.scheduleTrigger.kind }.toSet())
        assertEquals(1, restored.single { it.scheduleTrigger.kind == AlarmKind.STOP }.scheduleTrigger.deliveryAttempt)

        val startEntry = requireNotNull(restored.singleOrNull { it.scheduleTrigger.kind == AlarmKind.START })
        val retryTrigger = startEntry.scheduleTrigger.copy(deliveryAttempt = 1)
        val replacement = firstJournal.replace(
            startEntry.key,
            AlarmContract.triggerIntent(context, retryTrigger),
        )
        assertNotNull(replacement)
        assertEquals(
            1,
            firstJournal.entries().single { it.scheduleTrigger.kind == AlarmKind.START }.scheduleTrigger.deliveryAttempt,
        )
        val reverted = firstJournal.replace(
            requireNotNull(replacement).key,
            AlarmContract.triggerIntent(context, startEntry.scheduleTrigger),
        )
        assertNotNull(reverted)
        assertEquals(
            0,
            firstJournal.entries().single { it.scheduleTrigger.kind == AlarmKind.START }.scheduleTrigger.deliveryAttempt,
        )

        firstJournal.entries().forEach { firstJournal.remove(it.key) }
        assertEquals(emptyList<AlarmDeliveryJournal.Entry>(), firstJournal.entries())
    }

    @Test
    fun rehearsalStopSurvivesJournalRecreationWithDeliveryAttempt() {
        val preferenceName = "alarm-journal-rehearsal-${System.nanoTime()}"
        val firstJournal = AlarmDeliveryJournal(context, preferenceName)
        val trigger = RehearsalStopTrigger(
            sessionId = SessionId("rehearsal-session"),
            expectedAt = Instant.parse("2026-08-10T05:31:00Z"),
            deliveryAttempt = 2,
        )
        requireNotNull(
            firstJournal.persist(RehearsalStopAlarmContract.triggerIntent(context, trigger)),
        )

        val restored = AlarmDeliveryJournal(context, preferenceName).entries().single()
        val restoredTrigger = (restored.work as AlarmDeliveryWork.RehearsalStop).trigger

        assertEquals(trigger, restoredTrigger)
        assertEquals(2, restoredTrigger.deliveryAttempt)
        assertEquals(true, firstJournal.remove(restored.key))
    }
}

private val AlarmDeliveryJournal.Entry.scheduleTrigger: AlarmTrigger
    get() = (work as AlarmDeliveryWork.Schedule).trigger
