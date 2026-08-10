package dev.po4yka.lenswake.alarm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmServiceLifecycleGateTest {
    @Test
    fun newerStartCannotBeStoppedWhenOlderWorkCompletes() {
        val gate = AlarmServiceLifecycleGate()
        val stoppedIds = mutableListOf<Int>()
        gate.onStart(startId = 10) { gate.workAccepted() }
        gate.onStart(startId = 11) { gate.workAccepted() }

        gate.complete(stoppedIds::add)

        assertTrue(stoppedIds.isEmpty())
        assertEquals(1, gate.pendingWorkForTest())

        gate.complete(stoppedIds::add)

        assertEquals(listOf(11), stoppedIds)
        assertEquals(0, gate.pendingWorkForTest())
    }

    @Test
    fun failedEnqueueRollsBackPendingWorkBeforeStopDecision() {
        val gate = AlarmServiceLifecycleGate()
        gate.onStart(startId = 3) {
            gate.workAccepted()
            gate.workRejected()
        }

        assertEquals(0, gate.pendingWorkForTest())
    }

    @Test
    fun rejectedNewStartDoesNotStopPreviouslyAcceptedWork() {
        val gate = AlarmServiceLifecycleGate()
        val stoppedIds = mutableListOf<Int>()
        gate.onStart(startId = 10) { gate.workAccepted() }

        val stopped = gate.onStart(startId = 11) {
            gate.stopIfIdle(stoppedIds::add)
        }

        assertEquals(false, stopped)
        assertTrue(stoppedIds.isEmpty())
        assertEquals(1, gate.pendingWorkForTest())

        gate.complete(stoppedIds::add)

        assertEquals(listOf(11), stoppedIds)
    }
}
