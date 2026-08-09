package dev.po4yka.lenswake.alarm

/** Serializes service starts with work completion so a newly accepted start cannot be stopped. */
internal class AlarmServiceLifecycleGate {
    private val lock = Any()
    private var latestStartId = 0
    private var pendingWork = 0

    fun <T> onStart(startId: Int, block: () -> T): T = synchronized(lock) {
        latestStartId = startId
        block()
    }

    fun workAccepted() = synchronized(lock) {
        pendingWork += 1
    }

    fun workRejected() = synchronized(lock) {
        check(pendingWork > 0) { "Cannot reject work that was not accepted" }
        pendingWork -= 1
    }

    fun complete(stopLatest: (Int) -> Unit) = synchronized(lock) {
        check(pendingWork > 0) { "Cannot complete work that was not accepted" }
        pendingWork -= 1
        if (pendingWork == 0) stopLatest(latestStartId)
    }

    fun pendingWorkForTest(): Int = synchronized(lock) { pendingWork }
}
