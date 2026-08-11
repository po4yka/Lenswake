package dev.po4yka.lenswake.alarm

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

internal const val ACTION_ALARM_RECOVERY_RETRY = "dev.po4yka.lenswake.action.RETRY_ALARM_RECOVERY"

internal data class AlarmRecoveryCheckpoint(
    val attempt: Int,
    val lastFailure: String,
    val nextAttemptAtEpochMillis: Long?,
    val exhausted: Boolean,
    val updatedAtEpochMillis: Long,
    val reconcileInterruptedSessions: Boolean = false,
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
    private val storageContext = context.createDeviceProtectedStorageContext()
    private val preferences = storageContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    internal val isDeviceProtectedStorage: Boolean
        get() = storageContext.isDeviceProtectedStorage

    override fun checkpoint(): AlarmRecoveryCheckpoint? {
        val encoded = preferences.getString(KEY_CHECKPOINT, null) ?: return null
        return decode(encoded)
    }

    /** KTX edit returns Unit, but retry scheduling is gated on this synchronous commit result. */
    @SuppressLint("UseKtx")
    override fun persist(checkpoint: AlarmRecoveryCheckpoint): Boolean = preferences.edit()
        .putString(KEY_CHECKPOINT, encode(checkpoint))
        .commit()

    /** KTX edit returns Unit, but callers need to know whether durable checkpoint removal succeeded. */
    @SuppressLint("UseKtx")
    override fun clear(): Boolean = preferences.edit().remove(KEY_CHECKPOINT).commit()

    private fun encode(checkpoint: AlarmRecoveryCheckpoint): String = listOf(
        FORMAT_VERSION,
        checkpoint.attempt.toString(),
        checkpoint.nextAttemptAtEpochMillis?.toString().orEmpty(),
        checkpoint.exhausted.toString(),
        checkpoint.updatedAtEpochMillis.toString(),
        checkpoint.reconcileInterruptedSessions.toString(),
        Base64.encodeToString(
            checkpoint.lastFailure.toByteArray(StandardCharsets.UTF_8),
            BASE64_FLAGS,
        ),
    ).joinToString(SEPARATOR)

    private fun decode(encoded: String): AlarmRecoveryCheckpoint? = runCatching {
        val fields = encoded.split(SEPARATOR)
        val currentFormat = when {
            fields.size == FIELD_COUNT && fields[0] == FORMAT_VERSION -> true
            fields.size == LEGACY_FIELD_COUNT && fields[0] == LEGACY_FORMAT_VERSION -> false
            else -> return@runCatching null
        }
        val lastFailureField = if (currentFormat) {
            CURRENT_LAST_FAILURE_FIELD
        } else {
            LEGACY_LAST_FAILURE_FIELD
        }
        AlarmRecoveryCheckpoint(
            attempt = fields[1].toInt(),
            nextAttemptAtEpochMillis = fields[2].takeIf(String::isNotBlank)?.toLong(),
            exhausted = fields[3].toBooleanStrict(),
            updatedAtEpochMillis = fields[4].toLong(),
            reconcileInterruptedSessions = if (currentFormat) fields[5].toBooleanStrict() else false,
            lastFailure = String(
                Base64.decode(fields[lastFailureField], BASE64_FLAGS),
                StandardCharsets.UTF_8,
            ),
        )
    }.getOrNull()

    private companion object {
        const val PREFERENCE_NAME = "alarm_recovery_checkpoint"
        const val KEY_CHECKPOINT = "checkpoint"
        const val FORMAT_VERSION = "2"
        const val LEGACY_FORMAT_VERSION = "1"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 7
        const val LEGACY_FIELD_COUNT = 6
        const val BASE64_FLAGS = Base64.NO_WRAP or Base64.URL_SAFE
        const val CURRENT_LAST_FAILURE_FIELD = 6
        const val LEGACY_LAST_FAILURE_FIELD = 5
    }
}

internal interface AlarmRecoveryRetryBackend {
    fun schedule(triggerAtEpochMillis: Long): Result<Unit>
    fun cancel(): Boolean
}

internal class AndroidAlarmRecoveryRetryBackend(
    context: Context,
) : AlarmRecoveryRetryBackend {
    private val scheduler = AndroidAlarmRecoveryJobScheduler(context)

    override fun schedule(triggerAtEpochMillis: Long): Result<Unit> =
        scheduler.scheduleRetry(triggerAtEpochMillis)

    override fun cancel(): Boolean = scheduler.cancelRetry()
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
    ): AlarmRecoveryRetryResult = runCatching { persistence.checkpoint() }.fold(
        onSuccess = { checkpoint ->
            retry(checkpoint, detail, capabilityUnavailable)
        },
        onFailure = { error ->
            escalate(
                AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED,
                "$detail Recovery checkpoint could not be read: ${error.message.orEmpty()}",
            )
        },
    )

    private fun retry(
        checkpoint: AlarmRecoveryCheckpoint?,
        detail: String,
        capabilityUnavailable: Boolean,
    ): AlarmRecoveryRetryResult {
        val currentAttempt = checkpoint?.attempt ?: 0
        val reconcileInterruptedSessions = checkpoint?.reconcileInterruptedSessions == true
        return when {
            capabilityUnavailable -> {
                persistExhausted(currentAttempt, detail, reconcileInterruptedSessions)
                escalate(AlarmTransportFailureCode.RECOVERY_CAPABILITY_UNAVAILABLE, detail)
            }
            currentAttempt >= maxAttempts -> {
                persistExhausted(currentAttempt, detail, reconcileInterruptedSessions)
                escalate(AlarmTransportFailureCode.RECOVERY_ATTEMPTS_EXHAUSTED, detail)
            }
            else -> scheduleRetry(currentAttempt, detail, reconcileInterruptedSessions)
        }
    }

    private fun scheduleRetry(
        currentAttempt: Int,
        detail: String,
        reconcileInterruptedSessions: Boolean,
    ): AlarmRecoveryRetryResult {
        val nextAttempt = currentAttempt + 1
        val triggerAt = nowEpochMillis() + backoffMillis(nextAttempt)
        val checkpoint = AlarmRecoveryCheckpoint(
            attempt = nextAttempt,
            lastFailure = detail,
            nextAttemptAtEpochMillis = triggerAt,
            exhausted = false,
            updatedAtEpochMillis = nowEpochMillis(),
            reconcileInterruptedSessions = reconcileInterruptedSessions,
        )
        val persisted = runCatching { persistence.persist(checkpoint) }.getOrDefault(false)
        return if (!persisted) {
            escalate(
                AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED,
                "$detail Recovery checkpoint could not be persisted.",
            )
        } else {
            val scheduled = runCatching { backend.schedule(triggerAt) }
                .getOrElse { Result.failure(it) }
            if (scheduled.isSuccess) {
                AlarmRecoveryRetryResult.Scheduled(nextAttempt, triggerAt)
            } else {
                val schedulingDetail = "$detail Recovery requeue failed: " +
                    scheduled.exceptionOrNull()?.message.orEmpty()
                persistExhausted(
                    nextAttempt,
                    schedulingDetail.trim(),
                    reconcileInterruptedSessions,
                )
                escalate(
                    AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED,
                    schedulingDetail.trim(),
                )
            }
        }
    }

    fun retryWithScheduler(
        detail: String,
        capabilityUnavailable: Boolean = false,
    ): Boolean = runCatching { persistence.checkpoint() }.fold(
        onSuccess = { checkpoint ->
            retryWithScheduler(checkpoint, detail, capabilityUnavailable)
        },
        onFailure = { error ->
            escalate(
                AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED,
                "$detail Recovery checkpoint could not be read: ${error.message.orEmpty()}",
            )
            false
        },
    )

    private fun retryWithScheduler(
        checkpoint: AlarmRecoveryCheckpoint?,
        detail: String,
        capabilityUnavailable: Boolean,
    ): Boolean {
        val currentAttempt = checkpoint?.attempt ?: 0
        val reconcileInterruptedSessions = checkpoint?.reconcileInterruptedSessions == true
        return when {
            capabilityUnavailable -> {
                persistExhausted(currentAttempt, detail, reconcileInterruptedSessions)
                escalate(AlarmTransportFailureCode.RECOVERY_CAPABILITY_UNAVAILABLE, detail)
                false
            }
            currentAttempt >= maxAttempts -> {
                persistExhausted(currentAttempt, detail, reconcileInterruptedSessions)
                escalate(AlarmTransportFailureCode.RECOVERY_ATTEMPTS_EXHAUSTED, detail)
                false
            }
            else -> {
                val nextAttempt = currentAttempt + 1
                val triggerAt = nowEpochMillis() + backoffMillis(nextAttempt)
                val persisted = runCatching {
                    persistence.persist(
                        AlarmRecoveryCheckpoint(
                            attempt = nextAttempt,
                            lastFailure = detail,
                            nextAttemptAtEpochMillis = triggerAt,
                            exhausted = false,
                            updatedAtEpochMillis = nowEpochMillis(),
                            reconcileInterruptedSessions = reconcileInterruptedSessions,
                        ),
                    )
                }.getOrDefault(false)
                if (!persisted) {
                    escalate(
                        AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED,
                        "$detail Recovery checkpoint could not be persisted.",
                    )
                }
                persisted
            }
        }
    }

    fun resolve(cancelScheduledRetry: Boolean = true): Boolean {
        val cleared = runCatching { persistence.clear() }.getOrDefault(false)
        if (cancelScheduledRetry) runCatching { backend.cancel() }
        escalator.resolveRecovery()
        return cleared
    }

    private fun persistExhausted(
        attempt: Int,
        detail: String,
        reconcileInterruptedSessions: Boolean,
    ) {
        runCatching {
            persistence.persist(
                AlarmRecoveryCheckpoint(
                    attempt = attempt,
                    lastFailure = detail,
                    nextAttemptAtEpochMillis = null,
                    exhausted = true,
                    updatedAtEpochMillis = nowEpochMillis(),
                    reconcileInterruptedSessions = reconcileInterruptedSessions,
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
