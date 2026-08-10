package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.content.Context
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import java.time.Instant
import kotlinx.coroutines.flow.first

enum class SchedulingFailureCode {
    SCHEDULE_NOT_PERSISTED,
    REHEARSAL_SESSION_NOT_PERSISTED,
    STALE_SCHEDULE_REVISION,
    EXACT_ALARM_UNAVAILABLE,
    SCHEDULE_DISABLED,
    INVALID_TIME_RANGE,
    START_NOT_IN_FUTURE,
    STOP_NOT_IN_FUTURE,
    REHEARSAL_STOP_NOT_IN_FUTURE,
    NOT_A_REHEARSAL_SESSION,
    ALARM_MANAGER_REJECTED,
}

class SchedulingException(
    val code: SchedulingFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal interface RecordingAlarmBackend {
    fun canScheduleExactAlarms(): Boolean
    fun arm(schedule: RecordingSchedule, kind: AlarmKind, triggerAt: Instant)
    fun cancel(scheduleId: ScheduleId, kind: AlarmKind)
}

class AlarmManagerRecordingScheduler internal constructor(
    private val scheduleRepository: ScheduleRepository,
    private val clock: LenswakeClock,
    private val backend: RecordingAlarmBackend,
) : RecordingScheduler {
    constructor(
        context: Context,
        scheduleRepository: ScheduleRepository,
        clock: LenswakeClock,
        exactAlarmCapability: ExactAlarmCapability? = null,
    ) : this(
        scheduleRepository = scheduleRepository,
        clock = clock,
        backend = AndroidRecordingAlarmBackend(context, exactAlarmCapability),
    )

    override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> =
        schedulePersistedSnapshot(schedule, AlarmKind.START)

    override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> =
        schedulePersistedSnapshot(schedule, AlarmKind.STOP)

    override suspend fun stageStart(schedule: RecordingSchedule): Result<Unit> =
        scheduleStagedSnapshot(schedule, AlarmKind.START)

    override suspend fun stageStop(schedule: RecordingSchedule): Result<Unit> =
        scheduleStagedSnapshot(schedule, AlarmKind.STOP)

    override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = runCatching {
        AlarmKind.entries.forEach { kind -> backend.cancel(scheduleId, kind) }
    }

    override suspend fun restoreAll(): Result<Unit> = runCatching {
        requireExactAlarmCapability()
        val now = clock.now()
        scheduleRepository.observeSchedules().first().forEach { observed ->
            // Schedule mutations share a mutex in production; this comparison also prevents a
            // stale Flow snapshot from touching identities when the scheduler is used directly.
            val schedule = scheduleRepository.get(observed.id)
            if (schedule != observed) return@forEach
            if (!schedule.enabled || !schedule.stopAt.isAfter(now)) {
                AlarmKind.entries.forEach { kind -> backend.cancel(schedule.id, kind) }
                return@forEach
            }

            // STOP is the safety backstop. Replace it before START and before removing stale START.
            register(schedule, AlarmKind.STOP, now)
            if (schedule.startAt.isAfter(now)) {
                register(schedule, AlarmKind.START, now)
            } else {
                backend.cancel(schedule.id, AlarmKind.START)
            }
        }
    }

    private suspend fun schedulePersistedSnapshot(
        schedule: RecordingSchedule,
        kind: AlarmKind,
    ): Result<Unit> = runCatching {
        val persisted = scheduleRepository.get(schedule.id) ?: throw SchedulingException(
            code = SchedulingFailureCode.SCHEDULE_NOT_PERSISTED,
            message = "Schedule ${schedule.id.value} must be persisted before alarm registration",
        )
        if (persisted.updatedAt.toEpochMilli() != schedule.updatedAt.toEpochMilli()) {
            throw SchedulingException(
                code = SchedulingFailureCode.STALE_SCHEDULE_REVISION,
                message = "Alarm registration rejected a stale schedule revision",
            )
        }
        register(persisted, kind, clock.now())
    }

    private suspend fun scheduleStagedSnapshot(
        schedule: RecordingSchedule,
        kind: AlarmKind,
    ): Result<Unit> = runCatching {
        val persisted = scheduleRepository.get(schedule.id) ?: throw SchedulingException(
            code = SchedulingFailureCode.SCHEDULE_NOT_PERSISTED,
            message = "Schedule ${schedule.id.value} must be persisted before staged alarm registration",
        )
        if (persisted != schedule.canonicalPersistedSnapshot(enabled = false)) {
            throw SchedulingException(
                code = SchedulingFailureCode.STALE_SCHEDULE_REVISION,
                message = "Staged alarm registration requires the exact disabled schedule revision",
            )
        }
        register(schedule, kind, clock.now())
    }

    private fun RecordingSchedule.canonicalPersistedSnapshot(enabled: Boolean): RecordingSchedule = copy(
        startAt = Instant.ofEpochMilli(startAt.toEpochMilli()),
        stopAt = Instant.ofEpochMilli(stopAt.toEpochMilli()),
        enabled = enabled,
        createdAt = Instant.ofEpochMilli(createdAt.toEpochMilli()),
        updatedAt = Instant.ofEpochMilli(updatedAt.toEpochMilli()),
    )

    private fun register(
        schedule: RecordingSchedule,
        kind: AlarmKind,
        now: Instant,
    ) {
        val triggerAt = when (kind) {
            AlarmKind.START -> schedule.startAt
            AlarmKind.STOP -> schedule.stopAt
        }
        validate(schedule, kind, triggerAt, now)
        requireExactAlarmCapability()
        backend.arm(schedule, kind, triggerAt)
    }

    private fun requireExactAlarmCapability() {
        if (!backend.canScheduleExactAlarms()) {
            throw SchedulingException(
                code = SchedulingFailureCode.EXACT_ALARM_UNAVAILABLE,
                message = "Exact alarm access is unavailable; Lenswake will not schedule an inexact fallback",
            )
        }
    }

    private fun validate(
        schedule: RecordingSchedule,
        kind: AlarmKind,
        triggerAt: Instant,
        now: Instant,
    ) {
        if (!schedule.enabled) {
            throw SchedulingException(SchedulingFailureCode.SCHEDULE_DISABLED, "Schedule is disabled")
        }
        if (!schedule.stopAt.isAfter(schedule.startAt)) {
            throw SchedulingException(
                SchedulingFailureCode.INVALID_TIME_RANGE,
                "Stop time must be after start time",
            )
        }
        if (!triggerAt.isAfter(now)) {
            val code = when (kind) {
                AlarmKind.START -> SchedulingFailureCode.START_NOT_IN_FUTURE
                AlarmKind.STOP -> SchedulingFailureCode.STOP_NOT_IN_FUTURE
            }
            throw SchedulingException(code, "${kind.name.lowercase()} alarm must be in the future")
        }
    }
}

internal class AndroidRecordingAlarmBackend(
    context: Context,
    private val exactAlarmCapability: ExactAlarmCapability? = null,
) : RecordingAlarmBackend {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    override fun canScheduleExactAlarms(): Boolean =
        exactAlarmCapability?.canScheduleExactAlarms() ?: alarmManager.canScheduleExactAlarms()

    override fun arm(schedule: RecordingSchedule, kind: AlarmKind, triggerAt: Instant) {
        val pendingIntent = AutomationAlarmPendingIntentFactory.createOrUpdate(
            applicationContext,
            AlarmContract.requestCode(kind),
            AlarmContract.intent(applicationContext, schedule, kind),
        )
        try {
            AutomationAlarmPendingIntentFactory.armReplacementThenCancelLegacyGatewayActivityIdentities(
                context = applicationContext,
                alarmManager = alarmManager,
                requestCode = AlarmContract.requestCode(kind),
                legacyIdentityIntents = legacyIdentityIntents(schedule.id, kind),
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
                message = "Android rejected the exact ${kind.name.lowercase()} alarm",
                cause = error,
            )
        }
    }

    override fun cancel(scheduleId: ScheduleId, kind: AlarmKind) {
        cancelLegacyIdentity(scheduleId, kind, delivery = false)
        cancelLegacyIdentity(scheduleId, kind, delivery = true)
        val pendingIntent = AutomationAlarmPendingIntentFactory.find(
            applicationContext,
            AlarmContract.requestCode(kind),
            AlarmContract.identityIntent(applicationContext, scheduleId, kind),
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun cancelLegacyIdentity(
        scheduleId: ScheduleId,
        kind: AlarmKind,
        delivery: Boolean,
    ) {
        val identityIntent = if (delivery) {
            AlarmContract.deliveryIdentityIntent(applicationContext, scheduleId, kind)
        } else {
            AlarmContract.identityIntent(applicationContext, scheduleId, kind)
        }
        AutomationAlarmPendingIntentFactory.cancelLegacyGatewayActivityIdentity(
            context = applicationContext,
            alarmManager = alarmManager,
            requestCode = AlarmContract.requestCode(kind),
            identityIntent = identityIntent,
        )
    }

    private fun legacyIdentityIntents(scheduleId: ScheduleId, kind: AlarmKind) = listOf(
        AlarmContract.identityIntent(applicationContext, scheduleId, kind),
        AlarmContract.deliveryIdentityIntent(applicationContext, scheduleId, kind),
    )
}

fun interface ExactAlarmCapability {
    fun canScheduleExactAlarms(): Boolean
}
