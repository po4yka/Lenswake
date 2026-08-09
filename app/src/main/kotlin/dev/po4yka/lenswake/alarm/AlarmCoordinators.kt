package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.RecordingScheduler

sealed interface AlarmHandlingResult {
    data object Accepted : AlarmHandlingResult

    data class TerminalRejected(val reason: String) : AlarmHandlingResult

    data class Retryable(
        val reason: String,
        val cause: Throwable? = null,
    ) : AlarmHandlingResult
}

/**
 * Application seam responsible for stale-intent validation and idempotent START/STOP execution.
 * Returning [AlarmHandlingResult.Accepted] means work was durably accepted, not that recording has
 * started or stopped; the automation engine owns postcondition verification.
 */
fun interface AlarmTriggerCoordinator {
    suspend fun handle(trigger: AlarmTrigger): AlarmHandlingResult
}

fun interface RehearsalStopTriggerCoordinator {
    suspend fun handle(trigger: RehearsalStopTrigger): AlarmHandlingResult
}

/** Optional composition seam until the rehearsal application slice is wired. */
interface RehearsalStopComponentProvider {
    val rehearsalStopTriggerCoordinator: RehearsalStopTriggerCoordinator
}

fun interface AlarmRecoveryCoordinator {
    suspend fun restoreFutureSchedules(): Result<Unit>
}

fun interface AlarmRecoveryScheduler {
    suspend fun restoreAll(): Result<Unit>
}

class SchedulerAlarmRecoveryCoordinator(
    private val scheduler: RecordingScheduler,
    private val additionalSchedulers: List<AlarmRecoveryScheduler> = emptyList(),
) : AlarmRecoveryCoordinator {
    override suspend fun restoreFutureSchedules(): Result<Unit> {
        val results = mutableListOf(scheduler.restoreAll())
        for (additionalScheduler in additionalSchedulers) {
            results += additionalScheduler.restoreAll()
        }
        return results.firstOrNull(Result<Unit>::isFailure) ?: Result.success(Unit)
    }
}

/** Implemented by the application composition root; receivers fail explicitly when absent. */
interface AlarmComponentProvider {
    val alarmTriggerCoordinator: AlarmTriggerCoordinator
    val alarmRecoveryCoordinator: AlarmRecoveryCoordinator
}
