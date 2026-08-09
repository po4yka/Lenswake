package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.core.SessionId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RehearsalStopAlarmContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val sessionId = SessionId("alarm-contract-schedule")
    private val expectedAt = Instant.parse("2026-08-10T05:31:00Z")

    @Test
    fun rehearsalStopCannotAliasScheduleStopEvenWhenIdentifiersMatch() {
        val schedule = testSchedule()
        val scheduleStop = PendingIntent.getForegroundService(
            context,
            AlarmContract.requestCode(AlarmKind.STOP),
            AlarmContract.intent(context, schedule, AlarmKind.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rehearsalStop = PendingIntent.getForegroundService(
            context,
            RehearsalStopAlarmContract.REQUEST_CODE,
            RehearsalStopAlarmContract.intent(context, sessionId, expectedAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            assertNotEquals(scheduleStop, rehearsalStop)
            assertEquals(
                AutomationExecutionService::class.java.name,
                RehearsalStopAlarmContract.intent(context, sessionId, expectedAt).component?.className,
            )
            assertNotEquals(
                AlarmContract.intent(context, schedule, AlarmKind.STOP).action,
                RehearsalStopAlarmContract.intent(context, sessionId, expectedAt).action,
            )
        } finally {
            scheduleStop.cancel()
            rehearsalStop.cancel()
        }
    }

    @Test
    fun domainBackstopAndDeliveryRetryHaveIndependentIdentitiesAndCancellation() {
        val domainIntent = RehearsalStopAlarmContract.intent(context, sessionId, expectedAt)
        val retryTrigger = RehearsalStopTrigger(sessionId, expectedAt, deliveryAttempt = 2)
        val deliveryIntent = RehearsalStopAlarmContract.triggerIntent(context, retryTrigger)
        val domain = PendingIntent.getForegroundService(
            context,
            RehearsalStopAlarmContract.REQUEST_CODE,
            domainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val delivery = PendingIntent.getForegroundService(
            context,
            RehearsalStopAlarmContract.REQUEST_CODE,
            deliveryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            assertNotEquals(domain, delivery)
            assertEquals(expectedAt, RehearsalStopAlarmContract.parse(domainIntent)?.expectedAt)
            assertEquals(2, RehearsalStopAlarmContract.parse(deliveryIntent)?.deliveryAttempt)

            requireNotNull(
                PendingIntent.getForegroundService(
                    context,
                    RehearsalStopAlarmContract.REQUEST_CODE,
                    RehearsalStopAlarmContract.identityIntent(context, sessionId),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).cancel()

            assertNull(
                PendingIntent.getForegroundService(
                    context,
                    RehearsalStopAlarmContract.REQUEST_CODE,
                    RehearsalStopAlarmContract.identityIntent(context, sessionId),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            assertNotNull(
                PendingIntent.getForegroundService(
                    context,
                    RehearsalStopAlarmContract.REQUEST_CODE,
                    RehearsalStopAlarmContract.deliveryIdentityIntent(context, sessionId),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } finally {
            domain.cancel()
            delivery.cancel()
        }
    }

    @Test
    fun deliveryAttemptsReplaceOnlyDeliveryPayload() {
        val first = PendingIntent.getForegroundService(
            context,
            RehearsalStopAlarmContract.REQUEST_CODE,
            RehearsalStopAlarmContract.triggerIntent(
                context,
                RehearsalStopTrigger(sessionId, expectedAt, deliveryAttempt = 1),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val replacement = PendingIntent.getForegroundService(
            context,
            RehearsalStopAlarmContract.REQUEST_CODE,
            RehearsalStopAlarmContract.triggerIntent(
                context,
                RehearsalStopTrigger(sessionId, expectedAt, deliveryAttempt = 2),
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            assertEquals(first, replacement)
        } finally {
            first.cancel()
            replacement.cancel()
        }
    }

    @Test
    fun migrationCancelsGatewayRehearsalDomainAndDeliveryIdentities() {
        val domainIntent = RehearsalStopAlarmContract.intent(context, sessionId, expectedAt)
        val deliveryIntent = RehearsalStopAlarmContract.triggerIntent(
            context,
            RehearsalStopTrigger(sessionId, expectedAt, deliveryAttempt = 1),
        )
        val legacyDomain = legacyGatewayPendingIntent(domainIntent)
        val legacyDelivery = legacyGatewayPendingIntent(deliveryIntent)
        try {
            assertTrue(
                AutomationAlarmPendingIntentFactory.cancelLegacyGatewayActivityIdentity(
                    context,
                    alarmManager,
                    RehearsalStopAlarmContract.REQUEST_CODE,
                    RehearsalStopAlarmContract.identityIntent(context, sessionId),
                ),
            )
            assertTrue(
                AutomationAlarmPendingIntentFactory.cancelLegacyGatewayActivityIdentity(
                    context,
                    alarmManager,
                    RehearsalStopAlarmContract.REQUEST_CODE,
                    RehearsalStopAlarmContract.deliveryIdentityIntent(context, sessionId),
                ),
            )
            assertNull(findLegacyGatewayPendingIntent(domainIntent))
            assertNull(findLegacyGatewayPendingIntent(deliveryIntent))
        } finally {
            legacyDomain.cancel()
            legacyDelivery.cancel()
        }
    }

    @Test
    fun parserRejectsMalformedActionAndData() {
        val valid = RehearsalStopAlarmContract.intent(context, sessionId, expectedAt)

        assertNull(RehearsalStopAlarmContract.parse(Intent(valid).setAction("wrong")))
        assertNull(RehearsalStopAlarmContract.parse(Intent(valid).setData(null)))
    }

    private fun legacyGatewayPendingIntent(intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            RehearsalStopAlarmContract.REQUEST_CODE,
            AutomationAlarmPendingIntentFactory.legacyGatewayActivityIntent(context, intent),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun findLegacyGatewayPendingIntent(intent: Intent): PendingIntent? =
        PendingIntent.getActivity(
            context,
            RehearsalStopAlarmContract.REQUEST_CODE,
            AutomationAlarmPendingIntentFactory.legacyGatewayActivityIntent(context, intent),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
}
