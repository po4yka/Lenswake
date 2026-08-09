package dev.po4yka.lenswake.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal const val AUTOMATION_SERVICE_RESTART_MODE: Int = Service.START_REDELIVER_INTENT

/**
 * Exact alarms enter the durable automation path through this system-exempted foreground service.
 * Work is serialized, bounded, and always revalidated by the persisted [AlarmTriggerCoordinator].
 * Foreground-service admission does not prove background activity launch or Pixel Camera support.
 */
class AutomationExecutionService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.IO + CoroutineName("lenswake-alarm-execution"),
    )
    private val queue = Channel<QueuedTrigger>(Channel.UNLIMITED)
    private val queuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleGate = AlarmServiceLifecycleGate()
    private lateinit var notificationManager: NotificationManager
    private lateinit var journal: AlarmDeliveryJournal
    private lateinit var retryCoordinator: AlarmDeliveryRetryCoordinator
    private var journalRestored = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        journal = AlarmDeliveryJournal(this)
        retryCoordinator = AlarmDeliveryRetryCoordinator(
            backend = AndroidAlarmDeliveryRetryBackend(this, journal),
            escalator = AlarmTransportEscalator(
                persistence = SharedPreferencesAlarmTransportFailurePersistence(this),
                notifier = AndroidAlarmTransportFailureNotifier(this),
            ),
        )
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification("Preparing scheduled Pixel Camera automation"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
        serviceScope.launch {
            for (queued in queue) process(queued)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        lifecycleGate.onStart(startId) {
            val currentEntry = intent?.let(journal::persist)
            if (intent != null && currentEntry == null) {
                Log.e(TAG, "Rejected malformed foreground-service alarm intent")
                stopSelfResult(startId)
                return@onStart START_NOT_STICKY
            }
            val pendingEntries = if (!journalRestored) {
                journalRestored = true
                journal.entries()
            } else {
                listOfNotNull(currentEntry)
            }
            if (pendingEntries.isEmpty()) {
                Log.e(TAG, "No redelivered intent or durable journal entry is available")
                stopSelfResult(startId)
                return@onStart START_NOT_STICKY
            }
            val accepted = pendingEntries.map(::enqueue).all { it }
            if (!accepted) {
                Log.e(TAG, "Could not enqueue durable alarm journal entries")
                stopSelfResult(startId)
                return@onStart AUTOMATION_SERVICE_RESTART_MODE
            }
            AUTOMATION_SERVICE_RESTART_MODE
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        queue.close()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun process(queued: QueuedTrigger) {
        val trigger = queued.entry.trigger
        val elapsedBeforeExecution = SystemClock.elapsedRealtime() - queued.enqueuedAtElapsedRealtime
        val remainingDeadline = EXECUTION_DEADLINE_MILLIS - elapsedBeforeExecution
        var removeFromJournal = false
        if (remainingDeadline <= 0) {
            Log.e(TAG, "${trigger.kind} alarm expired while waiting for serialized execution")
            scheduleRetry(queued, "Alarm expired while waiting for serialized execution.")
            complete(queued, removeFromJournal)
            return
        }
        try {
            val provider = applicationContext as? AlarmComponentProvider
                ?: error("Application does not provide AlarmComponentProvider")
            when (val result = withTimeout(remainingDeadline) {
                provider.alarmTriggerCoordinator.handle(trigger)
            }) {
                AlarmHandlingResult.Accepted -> {
                    removeFromJournal = true
                    retryCoordinator.resolve(trigger)
                    Log.i(
                        TAG,
                        "${trigger.kind} automation completed for ${trigger.scheduleId.value}",
                    )
                }
                is AlarmHandlingResult.TerminalRejected -> {
                    removeFromJournal = true
                    retryCoordinator.resolve(trigger)
                    Log.e(
                        TAG,
                        "${trigger.kind} automation terminally rejected for ${trigger.scheduleId.value}: ${result.reason}",
                    )
                }
                is AlarmHandlingResult.Retryable -> {
                    scheduleRetry(queued, result.reason)
                    Log.e(
                        TAG,
                        "${trigger.kind} automation needs reconciliation for ${trigger.scheduleId.value}: ${result.reason}",
                        result.cause,
                    )
                }
            }
        } catch (error: TimeoutCancellationException) {
            scheduleRetry(queued, "Automation exceeded its finite service deadline.")
            Log.e(
                TAG,
                "${trigger.kind} automation exceeded the finite foreground-service deadline",
                error,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            scheduleRetry(queued, "Unhandled foreground-service failure: ${error.message.orEmpty()}")
            Log.e(TAG, "Unhandled ${trigger.kind} foreground-service failure", error)
        } finally {
            complete(queued, removeFromJournal)
        }
    }

    private fun enqueue(entry: AlarmDeliveryJournal.Entry): Boolean {
        if (!queuedKeys.add(entry.key)) return true
        lifecycleGate.workAccepted()
        val queued = QueuedTrigger(
            entry = entry,
            enqueuedAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )
        if (queue.trySend(queued).isSuccess) return true
        queuedKeys.remove(entry.key)
        lifecycleGate.workRejected()
        return false
    }

    private fun complete(queued: QueuedTrigger, removeFromJournal: Boolean) {
        if (removeFromJournal && !journal.remove(queued.entry.key)) {
            Log.e(TAG, "Could not clear completed durable alarm journal entry")
        }
        queuedKeys.remove(queued.entry.key)
        lifecycleGate.complete { latestStartId ->
            stopSelfResult(latestStartId)
        }
    }

    private fun scheduleRetry(queued: QueuedTrigger, detail: String) {
        when (val result = retryCoordinator.scheduleRetry(queued.entry, detail)) {
            AlarmDeliveryRetryResult.Scheduled -> Unit
            is AlarmDeliveryRetryResult.Escalated -> Log.e(
                TAG,
                "${queued.entry.trigger.kind} transport escalation ${result.code}; " +
                    "markerPersisted=${result.result.markerPersisted}, " +
                    "notification=${result.result.notification}; journal entry retained",
            )
        }
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Scheduled camera automation",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows while Lenswake handles an exact recording alarm"
                setShowBadge(false)
            },
        )
    }

    private fun notification(message: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("Lenswake scheduled automation")
        .setContentText(message)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()

    private data class QueuedTrigger(
        val entry: AlarmDeliveryJournal.Entry,
        val enqueuedAtElapsedRealtime: Long,
    )

    private companion object {
        const val TAG = "LenswakeAlarm"
        const val CHANNEL_ID = "scheduled_automation"
        const val NOTIFICATION_ID = 1_001
        const val EXECUTION_DEADLINE_MILLIS = 120_000L
    }
}
