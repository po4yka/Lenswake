package dev.po4yka.lenswake.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Base64
import dev.po4yka.lenswake.MainActivity
import java.nio.charset.StandardCharsets

internal enum class AlarmTransportFailureCode {
    RETRY_ATTEMPTS_EXHAUSTED,
    EXACT_ALARM_UNAVAILABLE,
    JOURNAL_UPDATE_FAILED,
    EXACT_ALARM_SCHEDULING_FAILED,
    STOP_TERMINAL_REJECTED,
    RECOVERY_ATTEMPTS_EXHAUSTED,
    RECOVERY_REQUEUE_FAILED,
    RECOVERY_CAPABILITY_UNAVAILABLE,
}

internal data class AlarmTransportFailureMarker(
    val id: String,
    val code: AlarmTransportFailureCode,
    val title: String,
    val message: String,
    val actionLabel: String,
    val cameraAction: Boolean,
    val recordedAtEpochMillis: Long,
)

internal interface AlarmTransportFailurePersistence {
    fun persist(marker: AlarmTransportFailureMarker): Boolean
    fun remove(id: String): Boolean
    fun markers(): List<AlarmTransportFailureMarker>
}

internal enum class FailureNotificationResult {
    PUBLISHED,
    PERMISSION_UNAVAILABLE,
    FAILED,
}

internal interface AlarmTransportFailureNotifier {
    fun publish(marker: AlarmTransportFailureMarker): FailureNotificationResult
    fun dismiss(id: String)
}

internal data class AlarmTransportEscalationResult(
    val markerPersisted: Boolean,
    val notification: FailureNotificationResult,
)

/** Durable failure state is authoritative; the notification is a permission-dependent signal. */
internal class AlarmTransportEscalator(
    private val persistence: AlarmTransportFailurePersistence,
    private val notifier: AlarmTransportFailureNotifier,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun escalateDelivery(
        work: AlarmDeliveryWork,
        code: AlarmTransportFailureCode,
        detail: String,
    ): AlarmTransportEscalationResult {
        val marker = AlarmTransportFailureMarker(
            id = work.markerId,
            code = code,
            title = if (work.isStop) {
                "Scheduled STOP needs manual action"
            } else {
                "Scheduled recording did not start"
            },
            message = if (work.isStop) {
                "Lenswake could not deliver STOP. Pixel Camera may still be recording; open Pixel Camera and stop it manually. $detail"
            } else {
                "Lenswake could not deliver START. The recording may not have started; open Lenswake and review the schedule. $detail"
            },
            actionLabel = if (work.isStop) "Open Pixel Camera" else "Open Lenswake",
            cameraAction = work.isStop,
            recordedAtEpochMillis = nowEpochMillis(),
        )
        return escalate(marker)
    }

    fun escalateRecovery(
        code: AlarmTransportFailureCode,
        detail: String,
    ): AlarmTransportEscalationResult = escalate(
        AlarmTransportFailureMarker(
            id = RECOVERY_MARKER_ID,
            code = code,
            title = "Scheduled alarms need attention",
            message = "Lenswake could not restore scheduled alarms. Open Lenswake and verify exact-alarm access before relying on schedules. $detail",
            actionLabel = "Open Lenswake",
            cameraAction = false,
            recordedAtEpochMillis = nowEpochMillis(),
        ),
    )

    fun resolveDelivery(work: AlarmDeliveryWork): Boolean = resolve(work.markerId)

    fun resolveRecovery(): Boolean = resolve(RECOVERY_MARKER_ID)

    private fun escalate(marker: AlarmTransportFailureMarker): AlarmTransportEscalationResult {
        val persisted = runCatching { persistence.persist(marker) }.getOrDefault(false)
        return AlarmTransportEscalationResult(
            markerPersisted = persisted,
            notification = runCatching { notifier.publish(marker) }
                .getOrDefault(FailureNotificationResult.FAILED),
        )
    }

    private fun resolve(id: String): Boolean {
        val removed = runCatching { persistence.remove(id) }.getOrDefault(false)
        runCatching { notifier.dismiss(id) }
        return removed
    }

    companion object {
        const val RECOVERY_MARKER_ID = "alarm-recovery"

        fun deliveryMarkerId(trigger: AlarmTrigger): String =
            AlarmDeliveryWork.Schedule(trigger).markerId
    }
}

internal class SharedPreferencesAlarmTransportFailurePersistence(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) : AlarmTransportFailurePersistence {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    override fun persist(marker: AlarmTransportFailureMarker): Boolean = preferences.edit()
        .putString(marker.id, encode(marker))
        .commit()

    override fun remove(id: String): Boolean = preferences.edit().remove(id).commit()

    override fun markers(): List<AlarmTransportFailureMarker> = preferences.all.mapNotNull { (id, value) ->
        decode(id, value as? String ?: return@mapNotNull null)
    }

    private fun encode(marker: AlarmTransportFailureMarker): String = listOf(
        FORMAT_VERSION,
        marker.code.name,
        marker.recordedAtEpochMillis.toString(),
        marker.cameraAction.toString(),
        encodeText(marker.title),
        encodeText(marker.message),
        encodeText(marker.actionLabel),
    ).joinToString(SEPARATOR)

    private fun decode(id: String, encoded: String): AlarmTransportFailureMarker? = runCatching {
        val fields = encoded.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields[0] != FORMAT_VERSION) return@runCatching null
        AlarmTransportFailureMarker(
            id = id,
            code = AlarmTransportFailureCode.valueOf(fields[1]),
            recordedAtEpochMillis = fields[2].toLong(),
            cameraAction = fields[3].toBooleanStrict(),
            title = decodeText(fields[4]),
            message = decodeText(fields[5]),
            actionLabel = decodeText(fields[6]),
        )
    }.getOrNull()

    private fun encodeText(value: String): String = Base64.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
        Base64.NO_WRAP or Base64.URL_SAFE,
    )

    private fun decodeText(value: String): String = String(
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE),
        StandardCharsets.UTF_8,
    )

    private companion object {
        const val PREFERENCE_NAME = "alarm_transport_failures"
        const val FORMAT_VERSION = "1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 7
    }
}

internal class AndroidAlarmTransportFailureNotifier(
    private val context: Context,
) : AlarmTransportFailureNotifier {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun publish(marker: AlarmTransportFailureMarker): FailureNotificationResult {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return FailureNotificationResult.PERMISSION_UNAVAILABLE
        }
        return try {
            createChannel()
            notificationManager.notify(notificationId(marker.id), notification(marker))
            FailureNotificationResult.PUBLISHED
        } catch (_: RuntimeException) {
            FailureNotificationResult.FAILED
        }
    }

    override fun dismiss(id: String) {
        runCatching { notificationManager.cancel(notificationId(id)) }
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Scheduled alarm failures",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Requires attention when scheduled camera automation cannot be delivered"
                setShowBadge(true)
            },
        )
    }

    private fun notification(marker: AlarmTransportFailureMarker): Notification {
        val actionIntent = if (marker.cameraAction) {
            context.packageManager.getLaunchIntentForPackage(PIXEL_CAMERA_PACKAGE)
        } else {
            null
        } ?: Intent(context, MainActivity::class.java)
        actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val action = PendingIntent.getActivity(
            context,
            notificationId(marker.id),
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(marker.title)
            .setContentText(marker.message)
            .setStyle(Notification.BigTextStyle().bigText(marker.message))
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(action)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_camera),
                    marker.actionLabel,
                    action,
                ).build(),
            )
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
    }

    private fun notificationId(id: String): Int = NOTIFICATION_ID_BASE +
        (id.hashCode() and NOTIFICATION_ID_MASK)

    private companion object {
        const val CHANNEL_ID = "alarm_transport_failures"
        const val NOTIFICATION_ID_BASE = 20_000
        const val NOTIFICATION_ID_MASK = 0x3fff_ffff
        const val PIXEL_CAMERA_PACKAGE = "com.google.android.GoogleCamera"
    }
}

internal interface AlarmDeliveryRetryBackend {
    fun canScheduleExactAlarms(): Boolean
    fun replaceJournalEntry(key: String, work: AlarmDeliveryWork): AlarmDeliveryJournal.Entry?
    fun schedule(work: AlarmDeliveryWork, triggerAtEpochMillis: Long): Result<Unit>
    fun restoreJournalEntry(key: String, work: AlarmDeliveryWork): Boolean
    fun cancel(work: AlarmDeliveryWork): Boolean
}

internal sealed interface AlarmDeliveryRetryResult {
    data object Scheduled : AlarmDeliveryRetryResult
    data class Escalated(
        val code: AlarmTransportFailureCode,
        val result: AlarmTransportEscalationResult,
    ) : AlarmDeliveryRetryResult
}

internal class AlarmDeliveryRetryCoordinator(
    private val backend: AlarmDeliveryRetryBackend,
    private val escalator: AlarmTransportEscalator,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = 2,
) {
    fun scheduleRetry(
        entry: AlarmDeliveryJournal.Entry,
        detail: String,
    ): AlarmDeliveryRetryResult {
        val work = entry.work
        if (work.deliveryAttempt >= maxAttempts) {
            return escalate(work, AlarmTransportFailureCode.RETRY_ATTEMPTS_EXHAUSTED, detail)
        }
        val exactAlarmsAvailable = runCatching { backend.canScheduleExactAlarms() }
            .getOrElse { error ->
                return escalate(
                    work,
                    AlarmTransportFailureCode.EXACT_ALARM_UNAVAILABLE,
                    "$detail Exact-alarm capability check failed: ${error.message.orEmpty()}",
                )
            }
        if (!exactAlarmsAvailable) {
            return escalate(work, AlarmTransportFailureCode.EXACT_ALARM_UNAVAILABLE, detail)
        }
        val retryWork = work.nextAttempt()
        val retryEntry = runCatching { backend.replaceJournalEntry(entry.key, retryWork) }.getOrNull()
            ?: return escalate(work, AlarmTransportFailureCode.JOURNAL_UPDATE_FAILED, detail)
        val scheduling = backend.schedule(retryWork, nowEpochMillis() + RETRY_DELAY_MILLIS)
        if (scheduling.isSuccess) return AlarmDeliveryRetryResult.Scheduled

        runCatching { backend.restoreJournalEntry(retryEntry.key, work) }
        return escalate(
            work,
            AlarmTransportFailureCode.EXACT_ALARM_SCHEDULING_FAILED,
            "$detail ${scheduling.exceptionOrNull()?.message.orEmpty()}".trim(),
        )
    }

    fun resolve(work: AlarmDeliveryWork): Boolean {
        runCatching { backend.cancel(work) }
        return escalator.resolveDelivery(work)
    }

    fun escalateTerminalStop(
        work: AlarmDeliveryWork,
        detail: String,
    ): AlarmTransportEscalationResult {
        require(work.isStop) { "Only STOP rejection needs manual-stop escalation" }
        runCatching { backend.cancel(work) }
        return escalator.escalateDelivery(
            work = work,
            code = AlarmTransportFailureCode.STOP_TERMINAL_REJECTED,
            detail = detail,
        )
    }

    private fun escalate(
        work: AlarmDeliveryWork,
        code: AlarmTransportFailureCode,
        detail: String,
    ): AlarmDeliveryRetryResult.Escalated = AlarmDeliveryRetryResult.Escalated(
        code = code,
        result = escalator.escalateDelivery(work, code, detail),
    )

    private companion object {
        const val RETRY_DELAY_MILLIS = 30_000L
    }
}

internal class AndroidAlarmDeliveryRetryBackend(
    private val context: Context,
    private val journal: AlarmDeliveryJournal,
) : AlarmDeliveryRetryBackend {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExactAlarms(): Boolean = alarmManager.canScheduleExactAlarms()

    override fun replaceJournalEntry(
        key: String,
        work: AlarmDeliveryWork,
    ): AlarmDeliveryJournal.Entry? = journal.replace(
        key,
        AlarmDeliveryWorkContract.triggerIntent(context, work),
    )

    override fun schedule(work: AlarmDeliveryWork, triggerAtEpochMillis: Long): Result<Unit> = runCatching {
        val pendingIntent = AutomationAlarmPendingIntentFactory.createOrUpdate(
            context,
            AlarmDeliveryWorkContract.requestCode(work),
            AlarmDeliveryWorkContract.triggerIntent(context, work),
        )
        AutomationAlarmPendingIntentFactory.armReplacementThenCancelLegacyGatewayActivityIdentities(
            context = context,
            alarmManager = alarmManager,
            requestCode = AlarmDeliveryWorkContract.requestCode(work),
            legacyIdentityIntents = listOf(
                AlarmDeliveryWorkContract.deliveryIdentityIntent(context, work),
            ),
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMillis,
                pendingIntent,
            )
        }
    }

    override fun restoreJournalEntry(key: String, work: AlarmDeliveryWork): Boolean =
        journal.replace(key, AlarmDeliveryWorkContract.triggerIntent(context, work)) != null

    override fun cancel(work: AlarmDeliveryWork): Boolean = runCatching {
        cancelLegacyDelivery(work)
        val pendingIntent = AutomationAlarmPendingIntentFactory.find(
            context,
            AlarmDeliveryWorkContract.requestCode(work),
            AlarmDeliveryWorkContract.deliveryIdentityIntent(context, work),
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        true
    }.getOrDefault(false)

    private fun cancelLegacyDelivery(work: AlarmDeliveryWork) {
        AutomationAlarmPendingIntentFactory.cancelLegacyGatewayActivityIdentity(
            context = context,
            alarmManager = alarmManager,
            requestCode = AlarmDeliveryWorkContract.requestCode(work),
            identityIntent = AlarmDeliveryWorkContract.deliveryIdentityIntent(context, work),
        )
    }
}
