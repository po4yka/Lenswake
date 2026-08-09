package dev.po4yka.lenswake.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal const val ALARM_RECOVERY_RESTART_MODE: Int = Service.START_REDELIVER_INTENT

/** Restores future alarms and re-arms durable delivery entries without invoking automation. */
class AlarmRecoveryService : Service() {
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("lenswake-alarm-recovery"),
    )
    private val queue = Channel<Unit>(Channel.UNLIMITED)
    private val lifecycleGate = AlarmServiceLifecycleGate()
    private lateinit var notificationManager: NotificationManager
    private lateinit var reconciler: AlarmJournalReconciler
    private lateinit var retryCoordinator: AlarmRecoveryRetryCoordinator

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        reconciler = AlarmJournalReconciler(
            journal = AlarmDeliveryJournal(this),
            backend = AndroidExactAlarmRearmBackend(this),
        )
        val escalator = AlarmTransportEscalator(
            persistence = SharedPreferencesAlarmTransportFailurePersistence(this),
            notifier = AndroidAlarmTransportFailureNotifier(this),
        )
        retryCoordinator = AlarmRecoveryRetryCoordinator(
            persistence = SharedPreferencesAlarmRecoveryCheckpointPersistence(this),
            backend = AndroidAlarmRecoveryRetryBackend(this),
            escalator = escalator,
        )
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
        serviceScope.launch {
            for (ignored in queue) recover()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        lifecycleGate.onStart(startId) {
            lifecycleGate.workAccepted()
            if (queue.trySend(Unit).isFailure) {
                lifecycleGate.workRejected()
                stopSelfResult(startId)
            }
            ALARM_RECOVERY_RESTART_MODE
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        queue.close()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun recover() {
        try {
            val failure = withTimeout(RECOVERY_DEADLINE_MILLIS) { attemptRecovery() }
            if (failure == null) {
                retryCoordinator.resolve()
                Log.i(TAG, "Alarm recovery completed and its durable checkpoint was cleared")
            } else {
                handleRecoveryFailure(failure)
            }
        } catch (error: TimeoutCancellationException) {
            Log.e(TAG, "Alarm recovery exceeded its finite deadline", error)
            handleRecoveryFailure(
                RecoveryFailure("Alarm recovery exceeded its finite deadline.", false),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            Log.e(TAG, "Alarm recovery failed", error)
            handleRecoveryFailure(
                RecoveryFailure("Alarm recovery failed: ${error.message.orEmpty()}", false),
            )
        } finally {
            lifecycleGate.complete { latestStartId -> stopSelfResult(latestStartId) }
        }
    }

    private suspend fun attemptRecovery(): RecoveryFailure? {
        val provider = applicationContext as? AlarmComponentProvider
            ?: error("Application does not provide AlarmComponentProvider")
        val failures = mutableListOf<String>()
        var capabilityUnavailable = false
        provider.alarmRecoveryCoordinator.restoreFutureSchedules()
            .onFailure { error ->
                if (error is CancellationException) throw error
                failures += "Future schedule restoration failed: ${error.message.orEmpty()}"
                capabilityUnavailable = error is SchedulingException &&
                    error.code == SchedulingFailureCode.EXACT_ALARM_UNAVAILABLE
                Log.e(TAG, "Future schedule restoration failed", error)
            }
        when (val result = reconciler.rearmAll()) {
            is JournalRearmResult.Rearmed -> Log.i(
                TAG,
                "Alarm recovery re-armed ${result.count} journaled triggers",
            )
            JournalRearmResult.ExactAlarmsUnavailable -> {
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

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Alarm recovery",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows briefly while Lenswake restores scheduled alarms"
                setShowBadge(false)
            },
        )
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("Lenswake alarm recovery")
        .setContentText("Restoring future scheduled alarms")
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()

    private data class RecoveryFailure(
        val detail: String,
        val capabilityUnavailable: Boolean,
    )

    private companion object {
        const val TAG = "LenswakeAlarm"
        const val CHANNEL_ID = "alarm_recovery"
        const val NOTIFICATION_ID = 1_002
        const val RECOVERY_DEADLINE_MILLIS = 30_000L
    }
}
