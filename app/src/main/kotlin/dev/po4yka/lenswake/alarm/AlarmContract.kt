package dev.po4yka.lenswake.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import java.time.Instant

enum class AlarmKind(val path: String) {
    START("start"),
    STOP("stop"),
}

data class AlarmTrigger(
    val kind: AlarmKind,
    val scheduleId: ScheduleId,
    val scheduleUpdatedAt: Instant,
    val expectedAt: Instant,
)

object AlarmContract {
    private const val SCHEME = "lenswake"
    private const val HOST = "schedule"
    private const val EXTRA_UPDATED_AT = "dev.po4yka.lenswake.extra.SCHEDULE_UPDATED_AT"
    private const val EXTRA_EXPECTED_AT = "dev.po4yka.lenswake.extra.EXPECTED_AT"
    private const val ACTION_START = "dev.po4yka.lenswake.action.START_RECORDING"
    private const val ACTION_STOP = "dev.po4yka.lenswake.action.STOP_RECORDING"

    fun intent(
        context: Context,
        schedule: RecordingSchedule,
        kind: AlarmKind,
    ): Intent {
        val receiverClass = when (kind) {
            AlarmKind.START -> StartAlarmReceiver::class.java
            AlarmKind.STOP -> StopAlarmReceiver::class.java
        }
        val expectedAt = when (kind) {
            AlarmKind.START -> schedule.startAt
            AlarmKind.STOP -> schedule.stopAt
        }
        return Intent(action(kind))
            .setComponent(ComponentName(context, receiverClass))
            .setData(uri(schedule.id, kind))
            .putExtra(EXTRA_UPDATED_AT, schedule.updatedAt.toEpochMilli())
            .putExtra(EXTRA_EXPECTED_AT, expectedAt.toEpochMilli())
    }

    fun identityIntent(
        context: Context,
        scheduleId: ScheduleId,
        kind: AlarmKind,
    ): Intent {
        val receiverClass = when (kind) {
            AlarmKind.START -> StartAlarmReceiver::class.java
            AlarmKind.STOP -> StopAlarmReceiver::class.java
        }
        return Intent(action(kind))
            .setComponent(ComponentName(context, receiverClass))
            .setData(uri(scheduleId, kind))
    }

    fun parse(intent: Intent, expectedKind: AlarmKind): AlarmTrigger? {
        if (intent.action != action(expectedKind)) return null
        val data = intent.data ?: return null
        if (data.scheme != SCHEME || data.host != HOST) return null
        val segments = data.pathSegments
        if (segments.size != 2 || segments[1] != expectedKind.path) return null
        val rawScheduleId = segments[0]
        if (rawScheduleId.isBlank()) return null
        if (!intent.hasExtra(EXTRA_UPDATED_AT) || !intent.hasExtra(EXTRA_EXPECTED_AT)) return null
        val updatedAtMillis = intent.getLongExtra(EXTRA_UPDATED_AT, Long.MIN_VALUE)
        val expectedAtMillis = intent.getLongExtra(EXTRA_EXPECTED_AT, Long.MIN_VALUE)
        if (updatedAtMillis == Long.MIN_VALUE || expectedAtMillis == Long.MIN_VALUE) return null
        return runCatching {
            AlarmTrigger(
                kind = expectedKind,
                scheduleId = ScheduleId(rawScheduleId),
                scheduleUpdatedAt = Instant.ofEpochMilli(updatedAtMillis),
                expectedAt = Instant.ofEpochMilli(expectedAtMillis),
            )
        }.getOrNull()
    }

    private fun action(kind: AlarmKind): String = when (kind) {
        AlarmKind.START -> ACTION_START
        AlarmKind.STOP -> ACTION_STOP
    }

    private fun uri(scheduleId: ScheduleId, kind: AlarmKind): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendPath(scheduleId.value)
        .appendPath(kind.path)
        .build()
}
