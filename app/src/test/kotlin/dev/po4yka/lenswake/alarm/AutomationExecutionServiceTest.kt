package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.ScheduleId
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationExecutionServiceTest {
    @Test
    fun `matching stop cancels and joins start before stop execution`() = runTest {
        val order = mutableListOf<String>()
        val startEntered = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val dispatcher = AlarmWorkDispatcher(
            scope = backgroundScope,
            execute = { queued ->
                when (queued.entry.work.scheduleKind()) {
                    AlarmKind.START -> try {
                        order += "start-entered"
                        startEntered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        order += "start-cancelled"
                    }
                    AlarmKind.STOP -> order += "stop-executed"
                    null -> error("Unexpected rehearsal work")
                }
            },
            onPreemptionTimeout = { error("Preemption unexpectedly timed out") },
            onCancelled = { cancelled += it.entry.key },
        )
        val start = queued("start", trigger("schedule-a", AlarmKind.START))
        val stop = queued("stop", trigger("schedule-a", AlarmKind.STOP))

        val restoredInArbitraryOrder = listOf(stop.entry, start.entry)
            .causalRestorationOrder()
            .map { entry -> QueuedTrigger(entry, enqueuedAtElapsedRealtime = 0) }
        assertEquals(listOf("start", "stop"), restoredInArbitraryOrder.map { it.entry.key })
        assertTrue(restoredInArbitraryOrder.all(dispatcher::dispatch))
        runCurrent()
        startEntered.await()

        assertEquals(
            listOf("start-entered", "start-cancelled", "stop-executed"),
            order,
        )
        assertEquals(listOf("start"), cancelled)
    }

    @Test
    fun `stop preempts only matching start while unrelated work stays independent`() = runTest {
        val entered = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val dispatcher = AlarmWorkDispatcher(
            scope = backgroundScope,
            execute = { queued ->
                val work = queued.entry.work as AlarmDeliveryWork.Schedule
                if (work.trigger.kind == AlarmKind.START) {
                    entered += work.trigger.scheduleId.value
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled += work.trigger.scheduleId.value
                    }
                } else {
                    entered += "stop:${work.trigger.scheduleId.value}"
                }
            },
            onPreemptionTimeout = { error("Preemption unexpectedly timed out") },
            onCancelled = {},
        )

        dispatcher.dispatch(queued("start-a", trigger("schedule-a", AlarmKind.START)))
        dispatcher.dispatch(queued("start-b", trigger("schedule-b", AlarmKind.START)))
        runCurrent()
        dispatcher.dispatch(queued("stop-a", trigger("schedule-a", AlarmKind.STOP)))
        runCurrent()

        assertEquals(
            listOf("schedule-a", "schedule-b", "stop:schedule-a"),
            entered,
        )
        assertEquals(listOf("schedule-a"), cancelled)
    }

    @Test
    fun `nonresponsive start makes preemption timeout bounded and observable`() = runTest {
        val timedOut = mutableListOf<String>()
        val dispatcher = AlarmWorkDispatcher(
            scope = backgroundScope,
            preemptionTimeoutMillis = 100,
            execute = { queued ->
                when (queued.entry.work.scheduleKind()) {
                    AlarmKind.START -> try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { delay(1_000) }
                    }
                    AlarmKind.STOP -> error("STOP must not overlap a nonresponsive START")
                    null -> error("Unexpected rehearsal work")
                }
            },
            onPreemptionTimeout = { timedOut += it.entry.key },
            onCancelled = {},
        )

        dispatcher.dispatch(queued("start", trigger("schedule-a", AlarmKind.START)))
        runCurrent()
        dispatcher.dispatch(queued("stop", trigger("schedule-a", AlarmKind.STOP)))
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf("stop"), timedOut)
    }

    private fun queued(key: String, trigger: AlarmTrigger) = QueuedTrigger(
        entry = AlarmDeliveryJournal.Entry(key, AlarmDeliveryWork.Schedule(trigger)),
        enqueuedAtElapsedRealtime = 0,
    )

    private fun trigger(scheduleId: String, kind: AlarmKind) = AlarmTrigger(
        kind = kind,
        scheduleId = ScheduleId(scheduleId),
        scheduleUpdatedAt = Instant.parse("2026-08-10T05:00:00Z"),
        expectedAt = Instant.parse("2026-08-10T05:30:00Z"),
    )
}

private fun AlarmDeliveryWork.scheduleKind(): AlarmKind? =
    (this as? AlarmDeliveryWork.Schedule)?.trigger?.kind
