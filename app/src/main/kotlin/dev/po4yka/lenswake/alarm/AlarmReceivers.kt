package dev.po4yka.lenswake.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

internal fun interface AlarmRecoveryServiceStarter {
    fun start(action: String): Result<Unit>
}

internal fun interface AlarmRecoveryFailureHandler {
    fun retry(detail: String): AlarmRecoveryRetryResult
}

internal sealed interface AlarmRecoveryBootstrapResult {
    data object Started : AlarmRecoveryBootstrapResult
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
    private val serviceStarter: AlarmRecoveryServiceStarter,
    private val failureHandler: AlarmRecoveryFailureHandler,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun handle(action: String, userUnlocked: Boolean): AlarmRecoveryBootstrapResult {
        if (action !in SUPPORTED_ACTIONS) return AlarmRecoveryBootstrapResult.Ignored
        val checkpoint = checkpointFor(action)
        if (!runCatching { persistence.persist(checkpoint) }.getOrDefault(false)) {
            return failureHandler.retry(
                "Recovery requested by $action, but its Direct Boot checkpoint could not be persisted.",
            ).toBootstrapResult()
        }
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED || !userUnlocked) {
            return AlarmRecoveryBootstrapResult.DeferredUntilUnlock
        }

        val startFailure = serviceStarter.start(action).exceptionOrNull()
            ?: return AlarmRecoveryBootstrapResult.Started
        return failureHandler.retry(
            "Recovery service admission failed for $action: " +
                "${startFailure.javaClass.simpleName}: ${startFailure.message.orEmpty()}",
        ).toBootstrapResult()
    }

    private fun checkpointFor(action: String): AlarmRecoveryCheckpoint {
        val existing = runCatching { persistence.checkpoint() }.getOrNull()
        val preserveAttempt = action == Intent.ACTION_USER_UNLOCKED ||
            action == ACTION_ALARM_RECOVERY_RETRY
        return if (preserveAttempt && existing != null) {
            existing.copy(
                lastFailure = "Recovery requested by $action. ${existing.lastFailure}",
                updatedAtEpochMillis = nowEpochMillis(),
            )
        } else {
            AlarmRecoveryCheckpoint(
                attempt = 0,
                lastFailure = "Recovery requested by $action.",
                nextAttemptAtEpochMillis = null,
                exhausted = false,
                updatedAtEpochMillis = nowEpochMillis(),
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

class AlarmRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in AlarmRecoveryBootstrapCoordinator.SUPPORTED_ACTIONS) return
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val persistence = SharedPreferencesAlarmRecoveryCheckpointPersistence(deviceProtectedContext)
        val escalator = AlarmTransportEscalator(
            persistence = SharedPreferencesAlarmTransportFailurePersistence(deviceProtectedContext),
            notifier = AndroidAlarmTransportFailureNotifier(context),
        )
        val retryCoordinator = AlarmRecoveryRetryCoordinator(
            persistence = persistence,
            backend = AndroidAlarmRecoveryRetryBackend(context),
            escalator = escalator,
        )
        val coordinator = AlarmRecoveryBootstrapCoordinator(
            persistence = persistence,
            serviceStarter = AlarmRecoveryServiceStarter { recoveryAction ->
                runCatching {
                    context.startForegroundService(
                        Intent(context, AlarmRecoveryService::class.java).setAction(recoveryAction),
                    )
                }.map { }
            },
            failureHandler = AlarmRecoveryFailureHandler { detail ->
                retryCoordinator.retry(detail)
            },
        )
        val userUnlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        when (val result = coordinator.handle(action, userUnlocked)) {
            AlarmRecoveryBootstrapResult.Started -> Unit
            AlarmRecoveryBootstrapResult.DeferredUntilUnlock -> Log.i(
                TAG,
                "Alarm recovery checkpoint retained until credential storage is unlocked",
            )
            is AlarmRecoveryBootstrapResult.Requeued -> Log.e(
                TAG,
                "Alarm recovery admission durably requeued as attempt ${result.attempt}",
            )
            is AlarmRecoveryBootstrapResult.Escalated -> Log.e(
                TAG,
                "Alarm recovery admission escalated with ${result.code}",
            )
            AlarmRecoveryBootstrapResult.Ignored -> Unit
        }
    }

    private companion object {
        const val TAG = "LenswakeAlarm"
    }
}
