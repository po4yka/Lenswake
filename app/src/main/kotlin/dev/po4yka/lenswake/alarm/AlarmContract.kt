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
    val deliveryAttempt: Int = 0,
) {
    init {
        require(deliveryAttempt >= 0) { "Delivery attempt must not be negative" }
    }
}

object AlarmContract {
    private const val SCHEME = "lenswake"
    private const val HOST = "schedule"
    private const val EXTRA_UPDATED_AT = "dev.po4yka.lenswake.extra.SCHEDULE_UPDATED_AT"
    private const val EXTRA_EXPECTED_AT = "dev.po4yka.lenswake.extra.EXPECTED_AT"
    private const val EXTRA_DELIVERY_ATTEMPT = "dev.po4yka.lenswake.extra.DELIVERY_ATTEMPT"
    private const val ACTION_START = "dev.po4yka.lenswake.action.START_RECORDING"
    private const val ACTION_STOP = "dev.po4yka.lenswake.action.STOP_RECORDING"
    private const val IDENTITY_PARAMETER = "identity"
    private const val DOMAIN_IDENTITY = "schedule"
    private const val DELIVERY_IDENTITY = "delivery"
    private const val START_REQUEST_CODE = 1_001
    private const val STOP_REQUEST_CODE = 1_002

    fun intent(
        context: Context,
        schedule: RecordingSchedule,
        kind: AlarmKind,
    ): Intent {
        val expectedAt = when (kind) {
            AlarmKind.START -> schedule.startAt
            AlarmKind.STOP -> schedule.stopAt
        }
        return intent(
            context,
            AlarmTrigger(
                kind = kind,
                scheduleId = schedule.id,
                scheduleUpdatedAt = schedule.updatedAt,
                expectedAt = expectedAt,
            ),
            DOMAIN_IDENTITY,
        )
    }

    fun triggerIntent(context: Context, trigger: AlarmTrigger): Intent =
        intent(context, trigger, DELIVERY_IDENTITY)

    private fun intent(
        context: Context,
        trigger: AlarmTrigger,
        identity: String,
    ): Intent =
        Intent(action(trigger.kind))
            .setComponent(ComponentName(context, AutomationExecutionService::class.java))
            .setData(uri(trigger.scheduleId, trigger.kind, identity))
            .putExtra(EXTRA_UPDATED_AT, trigger.scheduleUpdatedAt.toEpochMilli())
            .putExtra(EXTRA_EXPECTED_AT, trigger.expectedAt.toEpochMilli())
            .putExtra(EXTRA_DELIVERY_ATTEMPT, trigger.deliveryAttempt)

    fun identityIntent(
        context: Context,
        scheduleId: ScheduleId,
        kind: AlarmKind,
    ): Intent {
        return Intent(action(kind))
            .setComponent(ComponentName(context, AutomationExecutionService::class.java))
            .setData(uri(scheduleId, kind, DOMAIN_IDENTITY))
    }

    fun deliveryIdentityIntent(
        context: Context,
        scheduleId: ScheduleId,
        kind: AlarmKind,
    ): Intent {
        return Intent(action(kind))
            .setComponent(ComponentName(context, AutomationExecutionService::class.java))
            .setData(uri(scheduleId, kind, DELIVERY_IDENTITY))
    }

    fun parse(intent: Intent): AlarmTrigger? = when (intent.action) {
        ACTION_START -> parse(intent, AlarmKind.START)
        ACTION_STOP -> parse(intent, AlarmKind.STOP)
        else -> null
    }

    fun parse(intent: Intent, expectedKind: AlarmKind): AlarmTrigger? {
        val data = intent.data
        val segments = data?.pathSegments.orEmpty()
        val rawScheduleId = segments.firstOrNull().orEmpty()
        val validEnvelope = listOf(
            intent.action == action(expectedKind),
            data?.scheme == SCHEME,
            data?.host == HOST,
            segments.size == 2,
            segments.getOrNull(1) == expectedKind.path,
            data?.getQueryParameter(IDENTITY_PARAMETER) in setOf(DOMAIN_IDENTITY, DELIVERY_IDENTITY),
            rawScheduleId.isNotBlank(),
            intent.hasExtra(EXTRA_UPDATED_AT),
            intent.hasExtra(EXTRA_EXPECTED_AT),
        ).all { it }
        return if (!validEnvelope) {
            null
        } else {
            val updatedAtMillis = intent.getLongExtra(EXTRA_UPDATED_AT, Long.MIN_VALUE)
            val expectedAtMillis = intent.getLongExtra(EXTRA_EXPECTED_AT, Long.MIN_VALUE)
            val deliveryAttempt = intent.getIntExtra(EXTRA_DELIVERY_ATTEMPT, 0)
            val validPayload = listOf(
                updatedAtMillis != Long.MIN_VALUE,
                expectedAtMillis != Long.MIN_VALUE,
                deliveryAttempt >= 0,
            ).all { it }
            validPayload.takeIf { it }?.let {
                runCatching {
                    AlarmTrigger(
                        kind = expectedKind,
                        scheduleId = ScheduleId(rawScheduleId),
                        scheduleUpdatedAt = Instant.ofEpochMilli(updatedAtMillis),
                        expectedAt = Instant.ofEpochMilli(expectedAtMillis),
                        deliveryAttempt = deliveryAttempt,
                    )
                }.getOrNull()
            }
        }
    }

    private fun action(kind: AlarmKind): String = when (kind) {
        AlarmKind.START -> ACTION_START
        AlarmKind.STOP -> ACTION_STOP
    }

    fun requestCode(kind: AlarmKind): Int = when (kind) {
        AlarmKind.START -> START_REQUEST_CODE
        AlarmKind.STOP -> STOP_REQUEST_CODE
    }

    private fun uri(
        scheduleId: ScheduleId,
        kind: AlarmKind,
        identity: String,
    ): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendPath(scheduleId.value)
        .appendPath(kind.path)
        .appendQueryParameter(IDENTITY_PARAMETER, identity)
        .build()
}
