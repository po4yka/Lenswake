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
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.ui.AndroidUiStringProvider
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal const val AUTOMATION_SERVICE_RESTART_MODE: Int = Service.START_REDELIVER_INTENT

/**
 * Exact alarms enter the durable automation path through this system-exempted foreground service.
 * Work is bounded and always revalidated by the persisted [AlarmTriggerCoordinator]. A matching
 * STOP preempts in-flight START work; unrelated triggers remain independent. Foreground-service
 * admission does not prove background activity launch or Pixel Camera support.
 */
class AutomationExecutionService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.IO + CoroutineName("lenswake-alarm-execution"),
    )
    private val queuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleGate = AlarmServiceLifecycleGate()
    private lateinit var notificationManager: NotificationManager
    private lateinit var journal: AlarmDeliveryJournal
    private lateinit var retryCoordinator: AlarmDeliveryRetryCoordinator
    private lateinit var journalCorruptionHandler: AlarmJournalCorruptionDeliveryHandler
    private lateinit var dispatcher: AlarmWorkDispatcher
    private var journalRestored = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        journal = AlarmDeliveryJournal(this)
        val escalator = AlarmTransportEscalator(
            persistence = SharedPreferencesAlarmTransportFailurePersistence(this),
            notifier = AndroidAlarmTransportFailureNotifier(this),
            strings = AndroidUiStringProvider(this),
        )
        retryCoordinator = AlarmDeliveryRetryCoordinator(
            backend = AndroidAlarmDeliveryRetryBackend(this, journal),
            escalator = escalator,
        )
        journalCorruptionHandler = AlarmJournalCorruptionDeliveryHandler(
            escalator = escalator,
            recoveryCoordinator = AlarmRecoveryRetryCoordinator(
                persistence = SharedPreferencesAlarmRecoveryCheckpointPersistence(this),
                backend = AndroidAlarmRecoveryRetryBackend(this),
                escalator = escalator,
            ),
        )
        dispatcher = AlarmWorkDispatcher(
            scope = serviceScope,
            execute = ::process,
            onPreemptionTimeout = { queued ->
                scheduleRetry(queued, "STOP could not preempt matching START within its finite deadline.")
                complete(queued, removeFromJournal = false)
            },
            onCancelled = { queued -> complete(queued, removeFromJournal = false) },
        )
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(getString(R.string.automation_notification_preparing)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        lifecycleGate.onStart(startId) {
            val currentWork = intent?.let(AlarmDeliveryWorkContract::parse)
            if (intent != null && currentWork == null) {
                Log.e(TAG, "Rejected malformed foreground-service alarm intent")
                return@onStart stopIfIdleOrPreserveActiveWork()
            }
            val currentEntry = intent?.let { alarmIntent ->
                runCatching { journal.persist(alarmIntent) }
                    .onFailure { error ->
                        Log.e(TAG, "Initial durable alarm journal write threw", error)
                    }
                    .getOrNull()
            }
            if (currentWork != null && currentEntry == null) {
                recoverInitialJournalFailure(currentWork)
                return@onStart stopIfIdleOrPreserveActiveWork()
            }
            val pendingEntries = if (!journalRestored) {
                journalRestored = true
                val snapshot = journal.read()
                reportJournalCorruptions(snapshot.corruptEntries)
                snapshot.entries.causalRestorationOrder()
            } else {
                listOfNotNull(currentEntry)
            }
            if (pendingEntries.isEmpty()) {
                Log.e(TAG, "No redelivered intent or durable journal entry is available")
                return@onStart stopIfIdleOrPreserveActiveWork()
            }
            val accepted = pendingEntries.map(::enqueue).all { it }
            if (!accepted) {
                Log.e(TAG, "Could not enqueue durable alarm journal entries")
                return@onStart stopIfIdleOrPreserveActiveWork()
            }
            AUTOMATION_SERVICE_RESTART_MODE
        }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reportJournalCorruptions(entries: List<AlarmDeliveryJournal.CorruptEntry>) {
        val handling = journalCorruptionHandler.handle(entries)
        entries.zip(handling.escalations).forEach { (entry, result) ->
            Log.e(
                TAG,
                "Corrupt durable alarm journal entry detected (${entry.reason}); " +
                    "markerPersisted=${result.markerPersisted}, notification=${result.notification}",
            )
        }
        handling.recovery?.let { result ->
            Log.e(TAG, "Corrupt journal incident persistence requeued through alarm recovery: $result")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun process(queued: QueuedTrigger) {
        val work = queued.entry.work
        val elapsedBeforeExecution = SystemClock.elapsedRealtime() - queued.enqueuedAtElapsedRealtime
        val remainingDeadline = EXECUTION_DEADLINE_MILLIS - elapsedBeforeExecution
        var removeFromJournal = false
        if (remainingDeadline <= 0) {
            Log.e(TAG, "${work.displayKind} alarm expired while waiting for serialized execution")
            scheduleRetry(queued, "Alarm expired while waiting for serialized execution.")
            complete(queued, removeFromJournal)
            return
        }
        try {
            val provider = applicationContext as? AlarmComponentProvider
                ?: error("Application does not provide AlarmComponentProvider")
            when (val result = withTimeout(remainingDeadline) {
                handle(work, provider)
            }) {
                AlarmHandlingResult.Accepted -> {
                    removeFromJournal = true
                    retryCoordinator.resolve(work)
                    Log.i(
                        TAG,
                        "${work.displayKind} automation completed for ${work.targetId}",
                    )
                }
                is AlarmHandlingResult.TerminalRejected -> {
                    removeFromJournal = true
                    if (work.isStop) {
                        val escalation = retryCoordinator.escalateTerminalStop(
                            work,
                            result.reason,
                        )
                        Log.e(
                            TAG,
                            "STOP terminal rejection requires manual confirmation; " +
                                "markerPersisted=${escalation.markerPersisted}, " +
                                "notification=${escalation.notification}",
                        )
                    } else {
                        retryCoordinator.resolve(work)
                    }
                    Log.e(
                        TAG,
                        "${work.displayKind} automation terminally rejected for ${work.targetId}: ${result.reason}",
                    )
                }
                is AlarmHandlingResult.Retryable -> {
                    scheduleRetry(queued, result.reason)
                    Log.e(
                        TAG,
                        "${work.displayKind} automation needs reconciliation for ${work.targetId}: ${result.reason}",
                        result.cause,
                    )
                }
            }
        } catch (error: TimeoutCancellationException) {
            scheduleRetry(queued, "Automation exceeded its finite service deadline.")
            Log.e(
                TAG,
                "${work.displayKind} automation exceeded the finite foreground-service deadline",
                error,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            scheduleRetry(queued, "Unhandled foreground-service failure: ${error.message.orEmpty()}")
            Log.e(TAG, "Unhandled ${work.displayKind} foreground-service failure", error)
        } finally {
            complete(queued, removeFromJournal)
        }
    }

    private suspend fun handle(
        work: AlarmDeliveryWork,
        provider: AlarmComponentProvider,
    ): AlarmHandlingResult {
        return when (work) {
            is AlarmDeliveryWork.Schedule -> provider.alarmTriggerCoordinator.handle(work.trigger)
            is AlarmDeliveryWork.RehearsalStop -> {
                val rehearsalProvider = applicationContext as? RehearsalStopComponentProvider
                    ?: return AlarmHandlingResult.Retryable(
                        "Application does not provide RehearsalStopTriggerCoordinator",
                    )
                rehearsalProvider.rehearsalStopTriggerCoordinator.handle(work.trigger)
            }
        }
    }

    private fun enqueue(entry: AlarmDeliveryJournal.Entry): Boolean {
        if (!queuedKeys.add(entry.key)) return true
        lifecycleGate.workAccepted()
        val queued = QueuedTrigger(
            entry = entry,
            enqueuedAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )
        if (dispatcher.dispatch(queued)) return true
        queuedKeys.remove(entry.key)
        lifecycleGate.workRejected()
        return false
    }

    private fun complete(queued: QueuedTrigger, removeFromJournal: Boolean) {
        if (!queued.completed.compareAndSet(false, true)) return
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
                "${queued.entry.work.displayKind} transport escalation ${result.code}; " +
                    "markerPersisted=${result.result.markerPersisted}, " +
                    "notification=${result.result.notification}; journal entry retained",
            )
        }
    }

    private fun recoverInitialJournalFailure(work: AlarmDeliveryWork) {
        when (
            val result = retryCoordinator.scheduleUnjournaledRetry(
                work,
                "Initial durable alarm journal write failed.",
            )
        ) {
            AlarmDeliveryRetryResult.Scheduled -> Log.e(
                TAG,
                "${work.displayKind} initial journal write failed; independent exact retry scheduled",
            )
            is AlarmDeliveryRetryResult.Escalated -> Log.e(
                TAG,
                "${work.displayKind} initial journal failure escalation ${result.code}; " +
                    "markerPersisted=${result.result.markerPersisted}, " +
                    "notification=${result.result.notification}",
            )
        }
    }

    private fun stopIfIdleOrPreserveActiveWork(): Int =
        if (lifecycleGate.stopIfIdle { latestStartId -> stopSelfResult(latestStartId) }) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.automation_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.automation_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun notification(message: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle(getString(R.string.automation_notification_title))
        .setContentText(message)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()

    private companion object {
        const val TAG = "LenswakeAlarm"
        const val CHANNEL_ID = "scheduled_automation"
        const val NOTIFICATION_ID = 1_001
        const val EXECUTION_DEADLINE_MILLIS = 120_000L
    }
}

internal data class QueuedTrigger(
    val entry: AlarmDeliveryJournal.Entry,
    val enqueuedAtElapsedRealtime: Long,
    val completed: AtomicBoolean = AtomicBoolean(false),
)

/** Registers every restored schedule START before any STOP can attempt preemption. */
internal fun List<AlarmDeliveryJournal.Entry>.causalRestorationOrder(): List<AlarmDeliveryJournal.Entry> =
    sortedWith(
        compareBy<AlarmDeliveryJournal.Entry>(
            { entry ->
                val schedule = entry.work as? AlarmDeliveryWork.Schedule
                if (schedule?.trigger?.kind == AlarmKind.START) 0 else 1
            },
            { it.work.expectedAt },
            AlarmDeliveryJournal.Entry::key,
        ),
    )

internal class AlarmWorkDispatcher(
    private val scope: CoroutineScope,
    private val preemptionTimeoutMillis: Long = PREEMPTION_TIMEOUT_MILLIS,
    private val execute: suspend (QueuedTrigger) -> Unit,
    private val onPreemptionTimeout: (QueuedTrigger) -> Unit,
    private val onCancelled: (QueuedTrigger) -> Unit,
) {
    private val activeStarts = ConcurrentHashMap<String, ActiveStart>()

    init {
        require(preemptionTimeoutMillis > 0) { "Preemption timeout must be positive" }
    }

    fun dispatch(queued: QueuedTrigger): Boolean {
        if (!scope.isActive) return false
        val work = queued.entry.work
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (work is AlarmDeliveryWork.Schedule && work.trigger.kind == AlarmKind.STOP) {
                val matchingStarts = activeStarts.values
                    .filter { it.scheduleId == work.trigger.scheduleId }
                    .map(ActiveStart::job)
                matchingStarts.forEach(Job::cancel)
                try {
                    withTimeout(preemptionTimeoutMillis) { matchingStarts.joinAll() }
                } catch (_: TimeoutCancellationException) {
                    onPreemptionTimeout(queued)
                    return@launch
                }
            }
            execute(queued)
        }

        if (work is AlarmDeliveryWork.Schedule && work.trigger.kind == AlarmKind.START) {
            activeStarts[queued.entry.key] = ActiveStart(work.trigger.scheduleId, job)
            job.invokeOnCompletion {
                activeStarts.remove(queued.entry.key, ActiveStart(work.trigger.scheduleId, job))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) onCancelled(queued)
        }
        job.start()
        return true
    }

    private data class ActiveStart(
        val scheduleId: ScheduleId,
        val job: Job,
    )

    private companion object {
        const val PREEMPTION_TIMEOUT_MILLIS = 5_000L
    }
}
