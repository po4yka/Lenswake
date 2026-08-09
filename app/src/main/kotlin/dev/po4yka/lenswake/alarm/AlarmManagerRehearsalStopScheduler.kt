package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.content.Context
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import java.time.Instant

interface RehearsalStopBackstop : AlarmRecoveryScheduler {
    suspend fun schedule(sessionId: SessionId): Result<Unit>
    suspend fun cancel(sessionId: SessionId): Result<Unit>
}

/** Exact, independently recoverable STOP backstop addressed only by rehearsal [SessionId]. */
class AlarmManagerRehearsalStopScheduler internal constructor(
    private val executionRepository: ExecutionRepository,
    private val clock: LenswakeClock,
    private val backend: RehearsalStopAlarmBackend,
) : RehearsalStopBackstop {
    constructor(
        context: Context,
        executionRepository: ExecutionRepository,
        clock: LenswakeClock,
        exactAlarmCapability: ExactAlarmCapability? = null,
    ) : this(
        executionRepository = executionRepository,
        clock = clock,
        backend = AndroidRehearsalStopAlarmBackend(context, exactAlarmCapability),
    )

    override suspend fun schedule(sessionId: SessionId): Result<Unit> = runCatching {
        val session = executionRepository.get(sessionId) ?: throw SchedulingException(
            code = SchedulingFailureCode.REHEARSAL_SESSION_NOT_PERSISTED,
            message = "Rehearsal session ${sessionId.value} must be persisted before STOP registration",
        )
        validateRehearsal(session)
        if (!session.expectedStopAt.isAfter(clock.now())) {
            throw SchedulingException(
                code = SchedulingFailureCode.REHEARSAL_STOP_NOT_IN_FUTURE,
                message = "Rehearsal STOP must be in the future",
            )
        }
        requireExactAlarmCapability()
        register(session, session.expectedStopAt)
    }

    override suspend fun cancel(sessionId: SessionId): Result<Unit> = backend.cancel(sessionId)

    override suspend fun restoreAll(): Result<Unit> = runCatching {
        requireExactAlarmCapability()
        val now = clock.now()
        executionRepository.findActiveRehearsals(REHEARSAL_RESTORE_LIMIT).forEach { session ->
            validateRehearsal(session)
            if (session.stoppedVerifiedAt != null) {
                backend.cancel(session.id).getOrThrow()
                return@forEach
            }
            val future = session.expectedStopAt.isAfter(now)
            val hasOutstandingOwnership =
                session.recordActionAt != null && session.stoppedVerifiedAt == null
            if (future || hasOutstandingOwnership) {
                val triggerAt = if (future) {
                    session.expectedStopAt
                } else {
                    now.plusMillis(OVERDUE_RESTORE_DELAY_MILLIS)
                }
                register(session, triggerAt)
            } else {
                backend.cancel(session.id).getOrThrow()
            }
        }
    }

    private fun register(session: ExecutionSession, triggerAt: Instant) {
        backend.schedule(
            trigger = RehearsalStopTrigger(
                sessionId = session.id,
                expectedAt = session.expectedStopAt,
            ),
            triggerAt = triggerAt,
        ).getOrThrow()
    }

    private fun validateRehearsal(session: ExecutionSession) {
        if (session.kind != SessionKind.REHEARSAL) {
            throw SchedulingException(
                code = SchedulingFailureCode.NOT_A_REHEARSAL_SESSION,
                message = "Session ${session.id.value} is not a rehearsal",
            )
        }
    }

    private fun requireExactAlarmCapability() {
        if (!backend.canScheduleExactAlarms()) {
            throw SchedulingException(
                code = SchedulingFailureCode.EXACT_ALARM_UNAVAILABLE,
                message = "Exact alarm access is unavailable; rehearsal will not start without a STOP backstop",
            )
        }
    }

    private companion object {
        const val REHEARSAL_RESTORE_LIMIT = 100
        const val OVERDUE_RESTORE_DELAY_MILLIS = 1_000L
    }
}

internal interface RehearsalStopAlarmBackend {
    fun canScheduleExactAlarms(): Boolean
    fun schedule(trigger: RehearsalStopTrigger, triggerAt: Instant): Result<Unit>
    fun cancel(sessionId: SessionId): Result<Unit>
}

internal class AndroidRehearsalStopAlarmBackend(
    context: Context,
    private val exactAlarmCapability: ExactAlarmCapability? = null,
) : RehearsalStopAlarmBackend {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    override fun canScheduleExactAlarms(): Boolean =
        exactAlarmCapability?.canScheduleExactAlarms() ?: alarmManager.canScheduleExactAlarms()

    override fun schedule(trigger: RehearsalStopTrigger, triggerAt: Instant): Result<Unit> = runCatching {
        val pendingIntent = AutomationAlarmPendingIntentFactory.createOrUpdate(
            applicationContext,
            RehearsalStopAlarmContract.REQUEST_CODE,
            RehearsalStopAlarmContract.intent(
                applicationContext,
                trigger.sessionId,
                trigger.expectedAt,
            ),
        )
        try {
            AutomationAlarmPendingIntentFactory.armReplacementThenCancelLegacyGatewayActivityIdentities(
                context = applicationContext,
                alarmManager = alarmManager,
                requestCode = RehearsalStopAlarmContract.REQUEST_CODE,
                legacyIdentityIntents = legacyIdentityIntents(trigger.sessionId),
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt.toEpochMilli(),
                    pendingIntent,
                )
            }
        } catch (error: SecurityException) {
            throw SchedulingException(
                code = SchedulingFailureCode.ALARM_MANAGER_REJECTED,
                message = "Android rejected the exact rehearsal STOP alarm",
                cause = error,
            )
        }
    }

    override fun cancel(sessionId: SessionId): Result<Unit> = runCatching {
        cancelLegacyIdentities(sessionId)
        val pendingIntent = AutomationAlarmPendingIntentFactory.find(
            applicationContext,
            RehearsalStopAlarmContract.REQUEST_CODE,
            RehearsalStopAlarmContract.identityIntent(applicationContext, sessionId),
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun cancelLegacyIdentities(sessionId: SessionId) {
        legacyIdentityIntents(sessionId).forEach { identityIntent ->
            AutomationAlarmPendingIntentFactory.cancelLegacyGatewayActivityIdentity(
                context = applicationContext,
                alarmManager = alarmManager,
                requestCode = RehearsalStopAlarmContract.REQUEST_CODE,
                identityIntent = identityIntent,
            )
        }
    }

    private fun legacyIdentityIntents(sessionId: SessionId) = listOf(
        RehearsalStopAlarmContract.identityIntent(applicationContext, sessionId),
        RehearsalStopAlarmContract.deliveryIdentityIntent(applicationContext, sessionId),
    )
}
