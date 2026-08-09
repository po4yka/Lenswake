package dev.po4yka.lenswake.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
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
    private lateinit var alarmManager: AlarmManager
    private lateinit var journal: AlarmDeliveryJournal
    private var journalRestored = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        alarmManager = getSystemService(AlarmManager::class.java)
        journal = AlarmDeliveryJournal(this)
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
            scheduleRetry(queued)
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
                    Log.i(
                        TAG,
                        "${trigger.kind} automation completed for ${trigger.scheduleId.value}",
                    )
                }
                is AlarmHandlingResult.TerminalRejected -> {
                    removeFromJournal = true
                    Log.e(
                        TAG,
                        "${trigger.kind} automation terminally rejected for ${trigger.scheduleId.value}: ${result.reason}",
                    )
                }
                is AlarmHandlingResult.Retryable -> {
                    scheduleRetry(queued)
                    Log.e(
                        TAG,
                        "${trigger.kind} automation needs reconciliation for ${trigger.scheduleId.value}: ${result.reason}",
                        result.cause,
                    )
                }
            }
        } catch (error: TimeoutCancellationException) {
            scheduleRetry(queued)
            Log.e(
                TAG,
                "${trigger.kind} automation exceeded the finite foreground-service deadline",
                error,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            scheduleRetry(queued)
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

    private fun scheduleRetry(queued: QueuedTrigger) {
        val trigger = queued.entry.trigger
        if (trigger.deliveryAttempt >= MAX_RECONCILIATION_ATTEMPTS) {
            Log.e(
                TAG,
                "${trigger.kind} exhausted durable reconciliation attempts; journal entry retained",
            )
            return
        }
        if (!alarmManager.canScheduleExactAlarms()) {
            Log.e(TAG, "Cannot schedule durable reconciliation because exact alarms are unavailable")
            return
        }
        val retryTrigger = trigger.copy(deliveryAttempt = trigger.deliveryAttempt + 1)
        val retryIntent = AlarmContract.triggerIntent(this, retryTrigger)
        val retryEntry = journal.replace(queued.entry.key, retryIntent)
        if (retryEntry == null) {
            Log.e(TAG, "Could not persist the reconciliation trigger before exact scheduling")
            return
        }
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            AlarmContract.requestCode(trigger.kind),
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + RECONCILIATION_DELAY_MILLIS,
                pendingIntent,
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Android rejected the durable reconciliation exact alarm", error)
            restoreOriginalJournalEntry(retryEntry, trigger)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Durable reconciliation exact alarm failed", error)
            restoreOriginalJournalEntry(retryEntry, trigger)
        }
    }

    private fun restoreOriginalJournalEntry(
        retryEntry: AlarmDeliveryJournal.Entry,
        originalTrigger: AlarmTrigger,
    ) {
        if (
            journal.replace(
                retryEntry.key,
                AlarmContract.triggerIntent(this, originalTrigger),
            ) == null
        ) {
            Log.e(TAG, "Could not restore the original journal entry after retry scheduling failed")
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
        const val RECONCILIATION_DELAY_MILLIS = 30_000L
        const val MAX_RECONCILIATION_ATTEMPTS = 2
    }
}
