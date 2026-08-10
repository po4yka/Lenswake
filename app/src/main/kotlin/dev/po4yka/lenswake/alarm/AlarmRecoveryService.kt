package dev.po4yka.lenswake.alarm

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal fun shouldRetryStoppedRecovery(stopReason: Int): Boolean =
    stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP

/** Restores future alarms and re-arms durable delivery entries without invoking automation. */
class AlarmRecoveryService : JobService() {
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("lenswake-alarm-recovery"),
    )
    private val recoveryMutex = Mutex()
    private val terminalLock = Any()
    private val jobs = ConcurrentHashMap<Int, RecoveryJobRun>()
    private lateinit var reconciler: AlarmJournalReconciler
    private lateinit var escalator: AlarmTransportEscalator
    private lateinit var retryCoordinator: AlarmRecoveryRetryCoordinator
    private lateinit var checkpointPersistence: AlarmRecoveryCheckpointPersistence

    override fun onCreate() {
        super.onCreate()
        reconciler = AlarmJournalReconciler(
            journal = AlarmDeliveryJournal(this),
            backend = AndroidExactAlarmRearmBackend(this),
        )
        escalator = AlarmTransportEscalator(
            persistence = SharedPreferencesAlarmTransportFailurePersistence(this),
            notifier = AndroidAlarmTransportFailureNotifier(this),
        )
        checkpointPersistence = SharedPreferencesAlarmRecoveryCheckpointPersistence(this)
        retryCoordinator = AlarmRecoveryRetryCoordinator(
            persistence = checkpointPersistence,
            backend = AndroidAlarmRecoveryRetryBackend(this),
            escalator = escalator,
        )
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val action = params.extras.getString(EXTRA_RECOVERY_ACTION)
            ?: ACTION_ALARM_RECOVERY_RETRY
        val run = RecoveryJobRun()
        run.job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var shouldFinish = false
            var shouldReschedule = false
            try {
                recoveryMutex.withLock {
                    val failure = evaluateRecovery(action)
                    synchronized(terminalLock) {
                        synchronized(run) {
                            if (!run.stopped) {
                                shouldReschedule = commitRecovery(action, failure)
                                run.terminalCommitted = true
                                shouldFinish = true
                            }
                        }
                    }
                }
                if (shouldFinish) jobFinished(params, shouldReschedule)
            } finally {
                jobs.remove(params.jobId, run)
            }
        }
        jobs.put(params.jobId, run)?.let { previous ->
            synchronized(terminalLock) {
                synchronized(previous) {
                    previous.stopped = true
                    previous.job.cancel()
                }
            }
        }
        run.job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val run = jobs.remove(params.jobId) ?: return false
        return synchronized(terminalLock) {
            synchronized(run) {
                if (run.terminalCommitted) {
                    false
                } else {
                    run.stopped = true
                    run.job.cancel()
                    if (shouldRetryStoppedRecovery(params.stopReason)) {
                        retryCoordinator.retryWithScheduler(
                            detail = "Alarm recovery job was stopped by Android " +
                                "(reason=${params.stopReason}).",
                        )
                    } else {
                        false
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun evaluateRecovery(action: String): RecoveryFailure? =
        try {
            withTimeout(RECOVERY_DEADLINE_MILLIS) { attemptRecovery(action) }
        } catch (error: TimeoutCancellationException) {
            Log.e(TAG, "Alarm recovery exceeded its finite deadline", error)
            RecoveryFailure("Alarm recovery exceeded its finite deadline.", false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            Log.e(TAG, "Alarm recovery failed", error)
            RecoveryFailure("Alarm recovery failed: ${error.message.orEmpty()}", false)
        }

    private fun commitRecovery(action: String, failure: RecoveryFailure?): Boolean {
        if (failure == null) {
            retryCoordinator.resolve(
                cancelScheduledRetry = action != ACTION_ALARM_RECOVERY_RETRY,
            )
            Log.i(TAG, "Alarm recovery completed and its durable checkpoint was cleared")
            return false
        }
        if (action == ACTION_ALARM_RECOVERY_RETRY) {
            return retryCoordinator.retryWithScheduler(
                detail = failure.detail,
                capabilityUnavailable = failure.capabilityUnavailable,
            )
        }
        handleRecoveryFailure(failure)
        return false
    }

    private suspend fun attemptRecovery(action: String): RecoveryFailure? {
        val provider = applicationContext as? AlarmComponentProvider
            ?: error("Application does not provide AlarmComponentProvider")
        val failures = mutableListOf<String>()
        var capabilityUnavailable = false
        val reconcileInterruptedSessions = runCatching {
            checkpointPersistence.checkpoint()?.reconcileInterruptedSessions == true
        }.getOrElse { error ->
            failures += "Recovery checkpoint could not be read: ${error.message.orEmpty()}"
            false
        }
        provider.alarmRecoveryCoordinator.restoreFutureSchedules(reconcileInterruptedSessions)
            .onFailure { error ->
                if (error is CancellationException) throw error
                failures += "Future schedule restoration failed: ${error.message.orEmpty()}"
                capabilityUnavailable = error is SchedulingException &&
                    error.code == SchedulingFailureCode.EXACT_ALARM_UNAVAILABLE
                Log.e(TAG, "Future schedule restoration failed", error)
            }
        Log.i(TAG, "Alarm recovery source action: $action")
        val rearmResult = reconciler.rearmAll()
        rearmResult.corruptEntries.forEach { entry ->
            val escalation = escalator.escalateJournalCorruption(entry)
            Log.e(
                TAG,
                "Corrupt durable alarm journal entry detected (${entry.reason}); " +
                    "markerPersisted=${escalation.markerPersisted}, " +
                    "notification=${escalation.notification}",
            )
            if (!escalation.markerPersisted) {
                failures += "A corrupt journal entry was detected but its durable incident could not be saved."
            }
        }
        when (val result = rearmResult) {
            is JournalRearmResult.Rearmed -> Log.i(
                TAG,
                "Alarm recovery re-armed ${result.count} journaled triggers",
            )
            is JournalRearmResult.ExactAlarmsUnavailable -> {
                capabilityUnavailable = true
                failures += "Journaled triggers retained because exact alarms are unavailable."
                Log.e(TAG, failures.last())
            }
            is JournalRearmResult.Failed -> {
                failures += "Journaled trigger re-arm failed: ${result.cause.message.orEmpty()}"
                Log.e(TAG, "Journaled trigger re-arm failed; entries retained", result.cause)
            }
        }
        return failures.takeIf { it.isNotEmpty() }?.let {
            RecoveryFailure(it.joinToString(" "), capabilityUnavailable)
        }
    }

    private fun handleRecoveryFailure(failure: RecoveryFailure) {
        when (
            val result = retryCoordinator.retry(
                detail = failure.detail,
                capabilityUnavailable = failure.capabilityUnavailable,
            )
        ) {
            is AlarmRecoveryRetryResult.Scheduled -> Log.e(
                TAG,
                "Alarm recovery attempt ${result.attempt} durably requeued for ${result.triggerAtEpochMillis}",
            )
            is AlarmRecoveryRetryResult.Escalated -> Log.e(
                TAG,
                "Alarm recovery escalation ${result.code}; " +
                    "markerPersisted=${result.result.markerPersisted}, " +
                    "notification=${result.result.notification}",
            )
        }
    }

    private data class RecoveryFailure(
        val detail: String,
        val capabilityUnavailable: Boolean,
    )

    private class RecoveryJobRun {
        lateinit var job: Job
        var stopped: Boolean = false
        var terminalCommitted: Boolean = false
    }

    private companion object {
        const val TAG = "LenswakeAlarm"
        const val RECOVERY_DEADLINE_MILLIS = 30_000L
    }
}
