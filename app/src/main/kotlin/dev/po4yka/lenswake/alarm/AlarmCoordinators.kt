package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

interface AlarmRecoveryCoordinator {
    suspend fun restoreFutureSchedules(
        reconcileInterruptedSessions: Boolean = false,
    ): Result<Unit>
}

fun interface AlarmRecoveryScheduler {
    suspend fun restoreAll(): Result<Unit>
}

fun interface InterruptedScheduledSessionRecovery {
    suspend fun reconcile(): Result<Unit>
}

fun interface AlarmRecoveryReadiness {
    suspend fun check(): Result<Unit>
}

class AlarmRecoveryReadinessException(message: String) : IllegalStateException(message)

class PreflightAlarmRecoveryReadiness(
    private val inspect: suspend () -> PreflightReport,
) : AlarmRecoveryReadiness {
    override suspend fun check(): Result<Unit> = runCatching {
        val checks = inspect().checks.associateBy { it.type }
        val blockers = REQUIRED_CHECKS.mapNotNull { type ->
            val check = checks[type]
            when {
                check == null -> "$type readiness evidence is missing"
                check.severity != PreflightSeverity.BLOCKING -> null
                check.status == PreflightStatus.PASSED -> null
                else -> check.message
            }
        }
        if (blockers.isNotEmpty()) {
            throw AlarmRecoveryReadinessException(
                "Alarm recovery runtime readiness is blocked: ${blockers.joinToString("; ")}",
            )
        }
    }

    private companion object {
        // ACCESSIBILITY_CONNECTED is deliberately not a pre-engine gate. The connection is
        // process-local and may be restored only after DEVICE_WAKE; the engine verifies it with
        // bounded Pixel Camera inspections after waking the display.
        val REQUIRED_CHECKS = setOf(
            PreflightCheckType.EXACT_ALARMS,
            PreflightCheckType.MEDIA_VIDEO_ACCESS,
            PreflightCheckType.PIXEL_CAMERA_INSTALLED,
            PreflightCheckType.SECURE_CAMERA_RESOLVES,
            PreflightCheckType.DEVICE_WAKE,
            PreflightCheckType.ACCESSIBILITY_ENABLED,
            PreflightCheckType.PROFILE_AVAILABLE,
            PreflightCheckType.PROFILE_COMPATIBILITY,
            PreflightCheckType.REHEARSAL_CURRENT,
            PreflightCheckType.BATTERY,
            PreflightCheckType.CHARGING,
            PreflightCheckType.STORAGE,
        )
    }
}

class MutexAlarmRecoveryScheduler(
    private val delegate: AlarmRecoveryScheduler,
    private val mutex: Mutex,
) : AlarmRecoveryScheduler {
    override suspend fun restoreAll(): Result<Unit> = mutex.withLock { delegate.restoreAll() }
}

class SchedulerAlarmRecoveryCoordinator(
    private val scheduler: RecordingScheduler,
    private val additionalSchedulers: List<AlarmRecoveryScheduler> = emptyList(),
    private val interruptedSessionRecovery: InterruptedScheduledSessionRecovery? = null,
    private val readiness: AlarmRecoveryReadiness? = null,
) : AlarmRecoveryCoordinator {
    override suspend fun restoreFutureSchedules(
        reconcileInterruptedSessions: Boolean,
    ): Result<Unit> {
        val results = mutableListOf<Result<Unit>>()
        if (reconcileInterruptedSessions) {
            results += interruptedSessionRecovery?.reconcile()
                ?: Result.failure(
                    IllegalStateException("Interrupted scheduled-session recovery is not configured"),
                )
        }
        results += scheduler.restoreAll()
        for (additionalScheduler in additionalSchedulers) {
            results += additionalScheduler.restoreAll()
        }
        readiness?.let { results += it.check() }
        return results.firstOrNull(Result<Unit>::isFailure) ?: Result.success(Unit)
    }
}

/** Implemented by the application composition root; receivers fail explicitly when absent. */
interface AlarmComponentProvider {
    val alarmTriggerCoordinator: AlarmTriggerCoordinator
    val alarmRecoveryCoordinator: AlarmRecoveryCoordinator
}
