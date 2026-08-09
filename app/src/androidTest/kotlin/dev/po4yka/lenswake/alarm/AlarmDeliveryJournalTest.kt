package dev.po4yka.lenswake.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

        assertEquals(setOf(AlarmKind.START, AlarmKind.STOP), restored.map { it.trigger.kind }.toSet())
        assertEquals(1, restored.single { it.trigger.kind == AlarmKind.STOP }.trigger.deliveryAttempt)

        val startEntry = requireNotNull(restored.singleOrNull { it.trigger.kind == AlarmKind.START })
        val retryTrigger = startEntry.trigger.copy(deliveryAttempt = 1)
        val replacement = firstJournal.replace(
            startEntry.key,
            AlarmContract.triggerIntent(context, retryTrigger),
        )
        assertNotNull(replacement)
        assertEquals(
            1,
            firstJournal.entries().single { it.trigger.kind == AlarmKind.START }.trigger.deliveryAttempt,
        )
        val reverted = firstJournal.replace(
            requireNotNull(replacement).key,
            AlarmContract.triggerIntent(context, startEntry.trigger),
        )
        assertNotNull(reverted)
        assertEquals(
            0,
            firstJournal.entries().single { it.trigger.kind == AlarmKind.START }.trigger.deliveryAttempt,
        )

        firstJournal.entries().forEach { firstJournal.remove(it.key) }
        assertEquals(emptyList<AlarmDeliveryJournal.Entry>(), firstJournal.entries())
    }
}
