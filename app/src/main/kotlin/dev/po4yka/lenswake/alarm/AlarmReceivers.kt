package dev.po4yka.lenswake.alarm

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.UserManager
import android.util.Log
import dev.po4yka.lenswake.ui.AndroidUiStringProvider

internal fun interface AlarmRecoveryJobScheduler {
    fun schedule(action: String): Result<Unit>
}

internal fun interface AlarmRecoveryFailureHandler {
    fun retry(detail: String, capabilityUnavailable: Boolean): AlarmRecoveryRetryResult
}

internal sealed interface AlarmRecoveryBootstrapResult {
    data object Scheduled : AlarmRecoveryBootstrapResult
    data object DeferredUntilUnlock : AlarmRecoveryBootstrapResult
    data class Requeued(
        val attempt: Int,
        val triggerAtEpochMillis: Long,
    ) : AlarmRecoveryBootstrapResult
    data class Escalated(val code: AlarmTransportFailureCode) : AlarmRecoveryBootstrapResult
    data object Ignored : AlarmRecoveryBootstrapResult
}

/** Direct-Boot-safe transport coordinator. It never opens Room or invokes Camera automation. */
internal class AlarmRecoveryBootstrapCoordinator(
    private val persistence: AlarmRecoveryCheckpointPersistence,
    private val jobScheduler: AlarmRecoveryJobScheduler,
    private val failureHandler: AlarmRecoveryFailureHandler,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun handle(action: String, userUnlocked: Boolean): AlarmRecoveryBootstrapResult {
        if (action !in SUPPORTED_ACTIONS) return AlarmRecoveryBootstrapResult.Ignored
        val checkpoint = checkpointFor(action)
        if (!runCatching { persistence.persist(checkpoint) }.getOrDefault(false)) {
            return failureHandler.retry(
                "Recovery requested by $action, but its Direct Boot checkpoint could not be persisted.",
                capabilityUnavailable = false,
            ).toBootstrapResult()
        }
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED || !userUnlocked) {
            return AlarmRecoveryBootstrapResult.DeferredUntilUnlock
        }

        val startFailure = jobScheduler.schedule(action).exceptionOrNull()
            ?: return AlarmRecoveryBootstrapResult.Scheduled
        return failureHandler.retry(
            "Recovery job scheduling failed for $action: " +
                "${startFailure.javaClass.simpleName}: ${startFailure.message.orEmpty()}",
            capabilityUnavailable = false,
        ).toBootstrapResult()
    }

    private fun checkpointFor(action: String): AlarmRecoveryCheckpoint {
        val existing = runCatching { persistence.checkpoint() }.getOrNull()
        val preserveAttempt = action == Intent.ACTION_USER_UNLOCKED ||
            action == ACTION_ALARM_RECOVERY_RETRY
        val requiresSessionReconciliation = action in setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_BOOT_COMPLETED,
        ) || existing?.reconcileInterruptedSessions == true
        return if (preserveAttempt && existing != null) {
            existing.copy(
                lastFailure = "Recovery requested by $action. ${existing.lastFailure}",
                updatedAtEpochMillis = nowEpochMillis(),
                reconcileInterruptedSessions = requiresSessionReconciliation,
            )
        } else {
            AlarmRecoveryCheckpoint(
                attempt = 0,
                lastFailure = "Recovery requested by $action.",
                nextAttemptAtEpochMillis = null,
                exhausted = false,
                updatedAtEpochMillis = nowEpochMillis(),
                reconcileInterruptedSessions = requiresSessionReconciliation,
            )
        }
    }

    private fun AlarmRecoveryRetryResult.toBootstrapResult(): AlarmRecoveryBootstrapResult =
        when (this) {
            is AlarmRecoveryRetryResult.Scheduled -> AlarmRecoveryBootstrapResult.Requeued(
                attempt = attempt,
                triggerAtEpochMillis = triggerAtEpochMillis,
            )
            is AlarmRecoveryRetryResult.Escalated -> AlarmRecoveryBootstrapResult.Escalated(code)
        }

    companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            ACTION_ALARM_RECOVERY_RETRY,
        )
    }
}

internal class AndroidAlarmRecoveryJobScheduler(
    context: Context,
) : AlarmRecoveryJobScheduler {
    private val componentName = ComponentName(context, AlarmRecoveryService::class.java)
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    override fun schedule(action: String): Result<Unit> = runCatching {
        val result = scheduler.schedule(jobInfo(action))
        check(result == JobScheduler.RESULT_SUCCESS) {
            "JobScheduler rejected alarm recovery"
        }
    }

    internal fun scheduleRetry(triggerAtEpochMillis: Long): Result<Unit> = runCatching {
        val result = scheduler.schedule(retryJobInfo(triggerAtEpochMillis))
        check(result == JobScheduler.RESULT_SUCCESS) {
            "JobScheduler rejected alarm recovery retry"
        }
    }

    internal fun cancelRetry(): Boolean = runCatching {
        scheduler.cancel(ALARM_RECOVERY_RETRY_JOB_ID)
        true
    }.getOrDefault(false)

    internal fun jobInfo(action: String): JobInfo {
        val extras = PersistableBundle().apply { putString(EXTRA_RECOVERY_ACTION, action) }
        return JobInfo.Builder(ALARM_RECOVERY_JOB_ID, componentName)
            .setExtras(extras)
            .setPersisted(true)
            .setExpedited(true)
            .build()
    }

    internal fun retryJobInfo(triggerAtEpochMillis: Long): JobInfo {
        val delayMillis = (triggerAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val extras = PersistableBundle().apply {
            putString(EXTRA_RECOVERY_ACTION, ACTION_ALARM_RECOVERY_RETRY)
        }
        return JobInfo.Builder(ALARM_RECOVERY_RETRY_JOB_ID, componentName)
            .setExtras(extras)
            .setPersisted(true)
            .setMinimumLatency(delayMillis)
            .build()
    }
}

internal const val ALARM_RECOVERY_JOB_ID = 1_002
internal const val ALARM_RECOVERY_RETRY_JOB_ID = 1_003
internal const val EXTRA_RECOVERY_ACTION = "alarm_recovery_action"

class AlarmRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in AlarmRecoveryBootstrapCoordinator.SUPPORTED_ACTIONS) return
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val persistence = SharedPreferencesAlarmRecoveryCheckpointPersistence(deviceProtectedContext)
        val escalator = AlarmTransportEscalator(
            persistence = SharedPreferencesAlarmTransportFailurePersistence(deviceProtectedContext),
            notifier = AndroidAlarmTransportFailureNotifier(context),
            strings = AndroidUiStringProvider(context),
        )
        val retryCoordinator = AlarmRecoveryRetryCoordinator(
            persistence = persistence,
            backend = AndroidAlarmRecoveryRetryBackend(context),
            escalator = escalator,
        )
        val coordinator = AlarmRecoveryBootstrapCoordinator(
            persistence = persistence,
            jobScheduler = AndroidAlarmRecoveryJobScheduler(context),
            failureHandler = AlarmRecoveryFailureHandler { detail, capabilityUnavailable ->
                retryCoordinator.retry(detail, capabilityUnavailable)
            },
        )
        val userUnlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        when (val result = coordinator.handle(action, userUnlocked)) {
            AlarmRecoveryBootstrapResult.Scheduled -> Unit
            AlarmRecoveryBootstrapResult.DeferredUntilUnlock -> Log.i(
                TAG,
                "Alarm recovery checkpoint retained until credential storage is unlocked",
            )
            is AlarmRecoveryBootstrapResult.Requeued -> Log.e(
                TAG,
                "Alarm recovery scheduling durably requeued as attempt ${result.attempt}",
            )
            is AlarmRecoveryBootstrapResult.Escalated -> Log.e(
                TAG,
                "Alarm recovery scheduling escalated with ${result.code}",
            )
            AlarmRecoveryBootstrapResult.Ignored -> Unit
        }
    }

    private companion object {
        const val TAG = "LenswakeAlarm"
    }
}
