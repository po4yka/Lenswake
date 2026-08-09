package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import kotlinx.coroutines.flow.first
import java.time.Instant

enum class SchedulingFailureCode {
    SCHEDULE_NOT_PERSISTED,
    STALE_SCHEDULE_SNAPSHOT,
    EXACT_ALARM_UNAVAILABLE,
    SCHEDULE_DISABLED,
    INVALID_TIME_RANGE,
    START_NOT_IN_FUTURE,
    STOP_NOT_IN_FUTURE,
    ALARM_MANAGER_REJECTED,
}

class SchedulingException(
    val code: SchedulingFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class AlarmManagerRecordingScheduler(
    context: Context,
    private val scheduleRepository: ScheduleRepository,
    private val clock: LenswakeClock,
    private val exactAlarmCapability: ExactAlarmCapability? = null,
) : RecordingScheduler {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> =
        schedulePersistedSnapshot(schedule, AlarmKind.START)

    override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> =
        schedulePersistedSnapshot(schedule, AlarmKind.STOP)

    override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = runCatching {
        AlarmKind.entries.forEach { kind ->
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                requestCode(kind),
                AlarmContract.identityIntent(applicationContext, scheduleId, kind),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
    }

    override suspend fun restoreAll(): Result<Unit> = runCatching {
        requireExactAlarmCapability()
        val now = clock.now()
        scheduleRepository.observeSchedules().first().forEach { schedule ->
            cancel(schedule.id).getOrThrow()
            if (!schedule.enabled) return@forEach
            if (schedule.startAt.isAfter(now)) {
                scheduleCurrent(schedule.id, AlarmKind.START).getOrThrow()
            }
            if (schedule.stopAt.isAfter(now)) {
                scheduleCurrent(schedule.id, AlarmKind.STOP).getOrThrow()
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
        if (persisted != schedule) {
            throw SchedulingException(
                code = SchedulingFailureCode.STALE_SCHEDULE_SNAPSHOT,
                message = "Alarm registration rejected a transient or stale schedule snapshot",
            )
        }
        register(persisted, kind)
    }

    private suspend fun scheduleCurrent(
        scheduleId: ScheduleId,
        kind: AlarmKind,
    ): Result<Unit> = runCatching {
        val persisted = scheduleRepository.get(scheduleId) ?: throw SchedulingException(
            code = SchedulingFailureCode.SCHEDULE_NOT_PERSISTED,
            message = "Schedule ${scheduleId.value} disappeared during alarm restoration",
        )
        register(persisted, kind)
    }

    private fun register(
        schedule: RecordingSchedule,
        kind: AlarmKind,
    ) {
        val triggerAt = when (kind) {
            AlarmKind.START -> schedule.startAt
            AlarmKind.STOP -> schedule.stopAt
        }
        validate(schedule, kind, triggerAt, clock.now())
        requireExactAlarmCapability()
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            requestCode(kind),
            AlarmContract.intent(applicationContext, schedule, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt.toEpochMilli(),
                pendingIntent,
            )
        } catch (error: SecurityException) {
            throw SchedulingException(
                code = SchedulingFailureCode.ALARM_MANAGER_REJECTED,
                message = "Android rejected the exact ${kind.name.lowercase()} alarm",
                cause = error,
            )
        }
    }

    private fun requireExactAlarmCapability() {
        val canSchedule = exactAlarmCapability?.canScheduleExactAlarms()
            ?: alarmManager.canScheduleExactAlarms()
        if (!canSchedule) {
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

    private fun requestCode(kind: AlarmKind): Int = when (kind) {
        AlarmKind.START -> START_REQUEST_CODE
        AlarmKind.STOP -> STOP_REQUEST_CODE
    }

    private companion object {
        const val START_REQUEST_CODE = 1_001
        const val STOP_REQUEST_CODE = 1_002
    }
}

fun interface ExactAlarmCapability {
    fun canScheduleExactAlarms(): Boolean
}
