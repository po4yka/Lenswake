package dev.po4yka.lenswake.alarm

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.po4yka.lenswake.core.SessionId
import java.time.Instant

data class RehearsalStopTrigger(
    val sessionId: SessionId,
    val expectedAt: Instant,
    val deliveryAttempt: Int = 0,
) {
    init {
        require(deliveryAttempt >= 0) { "Delivery attempt must not be negative" }
    }
}

/** Session-addressed STOP namespace; it never aliases a ScheduleId PendingIntent. */
object RehearsalStopAlarmContract {
    private const val SCHEME = "lenswake"
    private const val HOST = "session"
    private const val PATH_REHEARSAL_STOP = "rehearsal-stop"
    private const val ACTION_REHEARSAL_STOP = "dev.po4yka.lenswake.action.REHEARSAL_STOP"
    private const val EXTRA_EXPECTED_AT = "dev.po4yka.lenswake.extra.REHEARSAL_STOP_EXPECTED_AT"
    private const val EXTRA_DELIVERY_ATTEMPT =
        "dev.po4yka.lenswake.extra.REHEARSAL_STOP_DELIVERY_ATTEMPT"
    private const val IDENTITY_PARAMETER = "identity"
    private const val DOMAIN_IDENTITY = "rehearsal"
    private const val DELIVERY_IDENTITY = "delivery"

    const val REQUEST_CODE: Int = 2_001

    fun intent(
        context: Context,
        sessionId: SessionId,
        expectedAt: Instant,
    ): Intent = intent(
        context,
        RehearsalStopTrigger(sessionId = sessionId, expectedAt = expectedAt),
        DOMAIN_IDENTITY,
    )

    fun triggerIntent(context: Context, trigger: RehearsalStopTrigger): Intent =
        intent(context, trigger, DELIVERY_IDENTITY)

    fun identityIntent(context: Context, sessionId: SessionId): Intent =
        identityIntent(context, sessionId, DOMAIN_IDENTITY)

    fun deliveryIdentityIntent(context: Context, sessionId: SessionId): Intent =
        identityIntent(context, sessionId, DELIVERY_IDENTITY)

    fun parse(intent: Intent): RehearsalStopTrigger? {
        if (intent.action != ACTION_REHEARSAL_STOP) return null
        val data = intent.data ?: return null
        if (data.scheme != SCHEME || data.host != HOST) return null
        val segments = data.pathSegments
        if (segments.size != 2 || segments[1] != PATH_REHEARSAL_STOP) return null
        if (data.getQueryParameter(IDENTITY_PARAMETER) !in setOf(DOMAIN_IDENTITY, DELIVERY_IDENTITY)) {
            return null
        }
        if (!intent.hasExtra(EXTRA_EXPECTED_AT)) return null
        val expectedAtMillis = intent.getLongExtra(EXTRA_EXPECTED_AT, Long.MIN_VALUE)
        val deliveryAttempt = intent.getIntExtra(EXTRA_DELIVERY_ATTEMPT, 0)
        if (expectedAtMillis == Long.MIN_VALUE || deliveryAttempt < 0) return null
        return runCatching {
            RehearsalStopTrigger(
                sessionId = SessionId(segments[0]),
                expectedAt = Instant.ofEpochMilli(expectedAtMillis),
                deliveryAttempt = deliveryAttempt,
            )
        }.getOrNull()
    }

    private fun intent(
        context: Context,
        trigger: RehearsalStopTrigger,
        identity: String,
    ): Intent = identityIntent(context, trigger.sessionId, identity)
        .putExtra(EXTRA_EXPECTED_AT, trigger.expectedAt.toEpochMilli())
        .putExtra(EXTRA_DELIVERY_ATTEMPT, trigger.deliveryAttempt)

    private fun identityIntent(
        context: Context,
        sessionId: SessionId,
        identity: String,
    ): Intent = AlarmWakeIntentRouting.route(context, Intent(ACTION_REHEARSAL_STOP))
        .setData(
            Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST)
                .appendPath(sessionId.value)
                .appendPath(PATH_REHEARSAL_STOP)
                .appendQueryParameter(IDENTITY_PARAMETER, identity)
                .build(),
        )
}

internal sealed interface AlarmDeliveryWork {
    val deliveryAttempt: Int
    val expectedAt: Instant
    val isStop: Boolean
    val displayKind: String
    val targetId: String
    val markerId: String

    fun nextAttempt(): AlarmDeliveryWork

    data class Schedule(
        val trigger: AlarmTrigger,
    ) : AlarmDeliveryWork {
        override val deliveryAttempt: Int = trigger.deliveryAttempt
        override val expectedAt: Instant = trigger.expectedAt
        override val isStop: Boolean = trigger.kind == AlarmKind.STOP
        override val displayKind: String = trigger.kind.name
        override val targetId: String = trigger.scheduleId.value
        override val markerId: String = buildString {
            append("alarm-delivery/")
            append(trigger.scheduleId.value)
            append('/')
            append(trigger.kind.name)
            append('/')
            append(trigger.scheduleUpdatedAt.toEpochMilli())
            append('/')
            append(trigger.expectedAt.toEpochMilli())
        }

        override fun nextAttempt(): AlarmDeliveryWork = copy(
            trigger = trigger.copy(deliveryAttempt = deliveryAttempt + 1),
        )
    }

    data class RehearsalStop(
        val trigger: RehearsalStopTrigger,
    ) : AlarmDeliveryWork {
        override val deliveryAttempt: Int = trigger.deliveryAttempt
        override val expectedAt: Instant = trigger.expectedAt
        override val isStop: Boolean = true
        override val displayKind: String = "REHEARSAL_STOP"
        override val targetId: String = trigger.sessionId.value
        override val markerId: String = buildString {
            append("alarm-delivery/rehearsal/")
            append(trigger.sessionId.value)
            append('/')
            append(trigger.expectedAt.toEpochMilli())
        }

        override fun nextAttempt(): AlarmDeliveryWork = copy(
            trigger = trigger.copy(deliveryAttempt = deliveryAttempt + 1),
        )
    }
}

internal object AlarmDeliveryWorkContract {
    fun parse(intent: Intent): AlarmDeliveryWork? =
        AlarmContract.parse(intent)?.let(AlarmDeliveryWork::Schedule)
            ?: RehearsalStopAlarmContract.parse(intent)?.let(AlarmDeliveryWork::RehearsalStop)

    fun triggerIntent(context: Context, work: AlarmDeliveryWork): Intent = when (work) {
        is AlarmDeliveryWork.Schedule -> AlarmContract.triggerIntent(context, work.trigger)
        is AlarmDeliveryWork.RehearsalStop ->
            RehearsalStopAlarmContract.triggerIntent(context, work.trigger)
    }

    fun deliveryIdentityIntent(context: Context, work: AlarmDeliveryWork): Intent = when (work) {
        is AlarmDeliveryWork.Schedule -> AlarmContract.deliveryIdentityIntent(
            context,
            work.trigger.scheduleId,
            work.trigger.kind,
        )
        is AlarmDeliveryWork.RehearsalStop ->
            RehearsalStopAlarmContract.deliveryIdentityIntent(context, work.trigger.sessionId)
    }

    fun requestCode(work: AlarmDeliveryWork): Int = when (work) {
        is AlarmDeliveryWork.Schedule -> AlarmContract.requestCode(work.trigger.kind)
        is AlarmDeliveryWork.RehearsalStop -> RehearsalStopAlarmContract.REQUEST_CODE
    }
}
