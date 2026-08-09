package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import java.nio.charset.StandardCharsets

internal const val ACTION_ALARM_RECOVERY_RETRY = "dev.po4yka.lenswake.action.RETRY_ALARM_RECOVERY"

internal data class AlarmRecoveryCheckpoint(
    val attempt: Int,
    val lastFailure: String,
    val nextAttemptAtEpochMillis: Long?,
    val exhausted: Boolean,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(attempt >= 0) { "Recovery attempt must not be negative" }
        require(lastFailure.isNotBlank()) { "Recovery failure must not be blank" }
        require(!exhausted || nextAttemptAtEpochMillis == null) {
            "An exhausted recovery checkpoint cannot have a next attempt"
        }
    }
}

internal interface AlarmRecoveryCheckpointPersistence {
    fun checkpoint(): AlarmRecoveryCheckpoint?
    fun persist(checkpoint: AlarmRecoveryCheckpoint): Boolean
    fun clear(): Boolean
}

internal class SharedPreferencesAlarmRecoveryCheckpointPersistence(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) : AlarmRecoveryCheckpointPersistence {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    override fun checkpoint(): AlarmRecoveryCheckpoint? {
        val encoded = preferences.getString(KEY_CHECKPOINT, null) ?: return null
        return decode(encoded)
    }

    override fun persist(checkpoint: AlarmRecoveryCheckpoint): Boolean = preferences.edit()
        .putString(KEY_CHECKPOINT, encode(checkpoint))
        .commit()

    override fun clear(): Boolean = preferences.edit().remove(KEY_CHECKPOINT).commit()

    private fun encode(checkpoint: AlarmRecoveryCheckpoint): String = listOf(
        FORMAT_VERSION,
        checkpoint.attempt.toString(),
        checkpoint.nextAttemptAtEpochMillis?.toString().orEmpty(),
        checkpoint.exhausted.toString(),
        checkpoint.updatedAtEpochMillis.toString(),
        Base64.encodeToString(
            checkpoint.lastFailure.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        ),
    ).joinToString(SEPARATOR)

    private fun decode(encoded: String): AlarmRecoveryCheckpoint? = runCatching {
        val fields = encoded.split(SEPARATOR)
        if (fields.size != FIELD_COUNT || fields[0] != FORMAT_VERSION) return@runCatching null
        AlarmRecoveryCheckpoint(
            attempt = fields[1].toInt(),
            nextAttemptAtEpochMillis = fields[2].takeIf(String::isNotBlank)?.toLong(),
            exhausted = fields[3].toBooleanStrict(),
            updatedAtEpochMillis = fields[4].toLong(),
            lastFailure = String(
                Base64.decode(fields[5], Base64.NO_WRAP or Base64.URL_SAFE),
                StandardCharsets.UTF_8,
            ),
        )
    }.getOrNull()

    private companion object {
        const val PREFERENCE_NAME = "alarm_recovery_checkpoint"
        const val KEY_CHECKPOINT = "checkpoint"
        const val FORMAT_VERSION = "1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 6
    }
}

internal interface AlarmRecoveryRetryBackend {
    fun schedule(triggerAtEpochMillis: Long): Result<Unit>
    fun cancel(): Boolean
}

internal class AndroidAlarmRecoveryRetryBackend(
    private val context: Context,
) : AlarmRecoveryRetryBackend {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(triggerAtEpochMillis: Long): Result<Unit> = runCatching {
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            RECOVERY_RETRY_REQUEST_CODE,
            retryIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtEpochMillis,
            pendingIntent,
        )
    }

    override fun cancel(): Boolean = runCatching {
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            RECOVERY_RETRY_REQUEST_CODE,
            retryIntent(),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        true
    }.getOrDefault(false)

    private fun retryIntent(): Intent = Intent(context, AlarmRecoveryService::class.java)
        .setAction(ACTION_ALARM_RECOVERY_RETRY)

    private companion object {
        const val RECOVERY_RETRY_REQUEST_CODE = 1_003
    }
}

internal sealed interface AlarmRecoveryRetryResult {
    data class Scheduled(
        val attempt: Int,
        val triggerAtEpochMillis: Long,
    ) : AlarmRecoveryRetryResult

    data class Escalated(
        val code: AlarmTransportFailureCode,
        val result: AlarmTransportEscalationResult,
    ) : AlarmRecoveryRetryResult
}

internal class AlarmRecoveryRetryCoordinator(
    private val persistence: AlarmRecoveryCheckpointPersistence,
    private val backend: AlarmRecoveryRetryBackend,
    private val escalator: AlarmTransportEscalator,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = 2,
) {
    fun retry(
        detail: String,
        capabilityUnavailable: Boolean = false,
    ): AlarmRecoveryRetryResult {
        val persistedCheckpoint = runCatching { persistence.checkpoint() }
            .getOrElse { error ->
                return escalate(
                    AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED,
                    "$detail Recovery checkpoint could not be read: ${error.message.orEmpty()}",
                )
            }
        val currentAttempt = persistedCheckpoint?.attempt ?: 0
        if (capabilityUnavailable) {
            persistExhausted(currentAttempt, detail)
            return escalate(AlarmTransportFailureCode.RECOVERY_CAPABILITY_UNAVAILABLE, detail)
        }
        if (currentAttempt >= maxAttempts) {
            persistExhausted(currentAttempt, detail)
            return escalate(AlarmTransportFailureCode.RECOVERY_ATTEMPTS_EXHAUSTED, detail)
        }

        val nextAttempt = currentAttempt + 1
        val triggerAt = nowEpochMillis() + backoffMillis(nextAttempt)
        val checkpoint = AlarmRecoveryCheckpoint(
            attempt = nextAttempt,
            lastFailure = detail,
            nextAttemptAtEpochMillis = triggerAt,
            exhausted = false,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        if (!runCatching { persistence.persist(checkpoint) }.getOrDefault(false)) {
            return escalate(
                AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED,
                "$detail Recovery checkpoint could not be persisted.",
            )
        }
        val scheduled = runCatching { backend.schedule(triggerAt) }
            .getOrElse { Result.failure(it) }
        if (scheduled.isSuccess) return AlarmRecoveryRetryResult.Scheduled(nextAttempt, triggerAt)

        val schedulingDetail = "$detail Recovery requeue failed: " +
            scheduled.exceptionOrNull()?.message.orEmpty()
        persistExhausted(nextAttempt, schedulingDetail.trim())
        return escalate(AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED, schedulingDetail.trim())
    }

    fun resolve(): Boolean {
        val cleared = runCatching { persistence.clear() }.getOrDefault(false)
        runCatching { backend.cancel() }
        escalator.resolveRecovery()
        return cleared
    }

    private fun persistExhausted(attempt: Int, detail: String) {
        runCatching {
            persistence.persist(
                AlarmRecoveryCheckpoint(
                    attempt = attempt,
                    lastFailure = detail,
                    nextAttemptAtEpochMillis = null,
                    exhausted = true,
                    updatedAtEpochMillis = nowEpochMillis(),
                ),
            )
        }
    }

    private fun escalate(
        code: AlarmTransportFailureCode,
        detail: String,
    ): AlarmRecoveryRetryResult.Escalated = AlarmRecoveryRetryResult.Escalated(
        code = code,
        result = escalator.escalateRecovery(code, detail),
    )

    private fun backoffMillis(attempt: Int): Long =
        (INITIAL_BACKOFF_MILLIS shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MILLIS)

    private companion object {
        const val INITIAL_BACKOFF_MILLIS = 30_000L
        const val MAX_BACKOFF_MILLIS = 5 * 60_000L
    }
}
