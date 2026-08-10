package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes recovery against the same schedule mutation critical section used by [ScheduleWorkflow]. */
class MutexRecordingScheduler(
    private val delegate: RecordingScheduler,
    private val mutex: Mutex,
) : RecordingScheduler {
    override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> =
        mutex.withLock { delegate.scheduleStart(schedule) }

    override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> =
        mutex.withLock { delegate.scheduleStop(schedule) }

    override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> =
        mutex.withLock { delegate.cancel(scheduleId) }

    override suspend fun restoreAll(): Result<Unit> =
        mutex.withLock { delegate.restoreAll() }
}
