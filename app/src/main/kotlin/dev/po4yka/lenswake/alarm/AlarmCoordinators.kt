package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.RecordingScheduler

sealed interface AlarmHandlingResult {
    data object Accepted : AlarmHandlingResult

    data class Rejected(val reason: String, val cause: Throwable? = null) : AlarmHandlingResult
}

/**
 * Application seam responsible for stale-intent validation and idempotent START/STOP execution.
 * Returning [AlarmHandlingResult.Accepted] means work was durably accepted, not that recording has
 * started or stopped; the automation engine owns postcondition verification.
 */
fun interface AlarmTriggerCoordinator {
    suspend fun handle(trigger: AlarmTrigger): AlarmHandlingResult
}

fun interface AlarmRecoveryCoordinator {
    suspend fun restoreFutureSchedules(): Result<Unit>
}

class SchedulerAlarmRecoveryCoordinator(
    private val scheduler: RecordingScheduler,
) : AlarmRecoveryCoordinator {
    override suspend fun restoreFutureSchedules(): Result<Unit> = scheduler.restoreAll()
}

/** Implemented by the application composition root; receivers fail explicitly when absent. */
interface AlarmComponentProvider {
    val alarmTriggerCoordinator: AlarmTriggerCoordinator
    val alarmRecoveryCoordinator: AlarmRecoveryCoordinator
}
