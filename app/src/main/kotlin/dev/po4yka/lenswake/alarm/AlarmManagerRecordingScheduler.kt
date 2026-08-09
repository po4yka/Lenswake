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
) : RecordingScheduler {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> = schedule(
        schedule = schedule,
        kind = AlarmKind.START,
        triggerAt = schedule.startAt,
    )

    override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> = schedule(
        schedule = schedule,
        kind = AlarmKind.STOP,
        triggerAt = schedule.stopAt,
    )

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
                scheduleStart(schedule).getOrThrow()
            }
            if (schedule.stopAt.isAfter(now)) {
                scheduleStop(schedule).getOrThrow()
            }
        }
    }

    private fun schedule(
        schedule: RecordingSchedule,
        kind: AlarmKind,
        triggerAt: Instant,
    ): Result<Unit> = runCatching {
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
        if (!alarmManager.canScheduleExactAlarms()) {
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
