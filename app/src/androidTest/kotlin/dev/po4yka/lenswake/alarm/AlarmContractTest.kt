package dev.po4yka.lenswake.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val schedule = testSchedule()

    @Test
    fun startAndStopHaveIndependentPendingIntentIdentities() {
        val start = PendingIntent.getActivity(
            context,
            1_001,
            AlarmContract.intent(context, schedule, AlarmKind.START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getActivity(
            context,
            1_002,
            AlarmContract.intent(context, schedule, AlarmKind.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            assertNotEquals(start, stop)
            assertEquals(
                AlarmWakeGatewayActivity::class.java.name,
                AlarmContract.intent(context, schedule, AlarmKind.START).component?.className,
            )
            assertEquals(
                AlarmWakeGatewayActivity::class.java.name,
                AlarmContract.intent(context, schedule, AlarmKind.STOP).component?.className,
            )
        } finally {
            start.cancel()
            stop.cancel()
        }
    }

    @Test
    fun wakeFactoryCreatesActivityPendingIntentAndFindsTheSameIdentity() {
        val factorySchedule = schedule.copy(id = ScheduleId("wake-factory-schedule"))
        val intent = AlarmContract.intent(context, factorySchedule, AlarmKind.START)
        val pendingIntent = AlarmWakePendingIntentFactory.createOrUpdate(
            context,
            AlarmContract.requestCode(AlarmKind.START),
            intent,
        )
        try {
            assertTrue(pendingIntent.isActivity)
            val expectedGatewayFlags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            assertEquals(expectedGatewayFlags, intent.flags and expectedGatewayFlags)
            assertEquals(
                pendingIntent,
                AlarmWakePendingIntentFactory.find(
                    context,
                    AlarmContract.requestCode(AlarmKind.START),
                    AlarmContract.identityIntent(context, factorySchedule.id, AlarmKind.START),
                ),
            )
            assertNull(
                PendingIntent.getForegroundService(
                    context,
                    AlarmContract.requestCode(AlarmKind.START),
                    AlarmContract.identityIntent(context, factorySchedule.id, AlarmKind.START),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } finally {
            pendingIntent.cancel()
        }
    }

    @Test
    fun migrationCancelsLegacyScheduleDomainAndDeliveryWithoutTouchingGatewayIdentity() {
        val migrationSchedule = schedule.copy(id = ScheduleId("legacy-service-migration"))
        val domainIntent = AlarmContract.intent(context, migrationSchedule, AlarmKind.STOP)
        val deliveryIntent = AlarmContract.triggerIntent(
            context,
            requireNotNull(AlarmContract.parse(domainIntent)).copy(deliveryAttempt = 1),
        )
        val requestCode = AlarmContract.requestCode(AlarmKind.STOP)
        val legacyDomain = legacyServicePendingIntent(requestCode, domainIntent)
        val legacyDelivery = legacyServicePendingIntent(requestCode, deliveryIntent)
        val gatewayDomain = AlarmWakePendingIntentFactory.createOrUpdate(
            context,
            requestCode,
            domainIntent,
        )
        try {
            AlarmWakePendingIntentFactory.armReplacementThenCancelLegacyServiceIdentities(
                context = context,
                alarmManager = alarmManager,
                requestCode = requestCode,
                legacyIdentityIntents = listOf(
                    AlarmContract.identityIntent(context, migrationSchedule.id, AlarmKind.STOP),
                    AlarmContract.deliveryIdentityIntent(
                        context,
                        migrationSchedule.id,
                        AlarmKind.STOP,
                    ),
                ),
                armReplacement = {},
            )
            assertNull(findLegacyServicePendingIntent(requestCode, domainIntent))
            assertNull(findLegacyServicePendingIntent(requestCode, deliveryIntent))
            assertEquals(
                gatewayDomain,
                AlarmWakePendingIntentFactory.find(
                    context,
                    requestCode,
                    AlarmContract.identityIntent(context, migrationSchedule.id, AlarmKind.STOP),
                ),
            )
        } finally {
            legacyDomain.cancel()
            legacyDelivery.cancel()
            gatewayDomain.cancel()
        }
    }

    @Test
    fun rejectedActivityReplacementPreservesLegacyServiceAlarmIdentity() {
        val migrationSchedule = schedule.copy(id = ScheduleId("legacy-preserved-on-failure"))
        val domainIntent = AlarmContract.intent(context, migrationSchedule, AlarmKind.START)
        val requestCode = AlarmContract.requestCode(AlarmKind.START)
        val legacyDomain = legacyServicePendingIntent(requestCode, domainIntent)
        try {
            val failure = runCatching {
                AlarmWakePendingIntentFactory.armReplacementThenCancelLegacyServiceIdentities(
                    context = context,
                    alarmManager = alarmManager,
                    requestCode = requestCode,
                    legacyIdentityIntents = listOf(
                        AlarmContract.identityIntent(
                            context,
                            migrationSchedule.id,
                            AlarmKind.START,
                        ),
                    ),
                ) {
                    error("replacement rejected")
                }
            }.exceptionOrNull()

            assertEquals("replacement rejected", failure?.message)
            assertNotNull(findLegacyServicePendingIntent(requestCode, domainIntent))
        } finally {
            legacyDomain.cancel()
        }
    }

    @Test
    fun scheduleRevisionUpdatesPayloadWithoutChangingStartIdentity() {
        val initial = PendingIntent.getActivity(
            context,
            1_001,
            AlarmContract.intent(context, schedule, AlarmKind.START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val changed = schedule.copy(
            startAt = schedule.startAt.plusSeconds(60),
            stopAt = schedule.stopAt.plusSeconds(60),
            updatedAt = schedule.updatedAt.plusSeconds(30),
        )
        val replacement = PendingIntent.getActivity(
            context,
            1_001,
            AlarmContract.intent(context, changed, AlarmKind.START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            assertEquals(initial, replacement)
            val parsed = AlarmContract.parse(
                AlarmContract.intent(context, changed, AlarmKind.START),
                AlarmKind.START,
            )
            assertNotNull(parsed)
            assertEquals(changed.updatedAt, parsed?.scheduleUpdatedAt)
            assertEquals(changed.startAt, parsed?.expectedAt)
        } finally {
            initial.cancel()
            replacement.cancel()
        }
    }

    @Test
    fun restoredFutureScheduleAndJournalRetryCoexistAfterClockRollback() {
        val rolledBackNow = schedule.startAt.minusSeconds(3_600)
        assertTrue(schedule.startAt.isAfter(rolledBackNow))
        val futureDomainIntent = AlarmContract.intent(context, schedule, AlarmKind.START)
        val journalTrigger = requireNotNull(AlarmContract.parse(futureDomainIntent)).copy(
            deliveryAttempt = 1,
        )
        val deliveryIntent = AlarmContract.triggerIntent(context, journalTrigger)
        val domain = PendingIntent.getActivity(
            context,
            AlarmContract.requestCode(AlarmKind.START),
            futureDomainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val delivery = PendingIntent.getActivity(
            context,
            AlarmContract.requestCode(AlarmKind.START),
            deliveryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            assertNotEquals(futureDomainIntent.data, deliveryIntent.data)
            assertNotEquals(domain, delivery)
            assertEquals(schedule.startAt, AlarmContract.parse(futureDomainIntent)?.expectedAt)
            assertEquals(schedule.startAt, AlarmContract.parse(deliveryIntent)?.expectedAt)

            val domainIdentity = PendingIntent.getActivity(
                context,
                AlarmContract.requestCode(AlarmKind.START),
                AlarmContract.identityIntent(context, schedule.id, AlarmKind.START),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            val deliveryIdentity = PendingIntent.getActivity(
                context,
                AlarmContract.requestCode(AlarmKind.START),
                AlarmContract.deliveryIdentityIntent(context, schedule.id, AlarmKind.START),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            assertEquals(domain, domainIdentity)
            assertEquals(delivery, deliveryIdentity)

            requireNotNull(domainIdentity).cancel()
            assertNull(
                PendingIntent.getActivity(
                    context,
                    AlarmContract.requestCode(AlarmKind.START),
                    AlarmContract.identityIntent(context, schedule.id, AlarmKind.START),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            assertNotNull(
                PendingIntent.getActivity(
                    context,
                    AlarmContract.requestCode(AlarmKind.START),
                    AlarmContract.deliveryIdentityIntent(context, schedule.id, AlarmKind.START),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } finally {
            domain.cancel()
            delivery.cancel()
        }
    }

    @Test
    fun retryAttemptsReplaceOnlyDeliveryIdentityAndCanBeCancelledIndependently() {
        val domain = PendingIntent.getActivity(
            context,
            AlarmContract.requestCode(AlarmKind.STOP),
            AlarmContract.intent(context, schedule, AlarmKind.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val trigger = requireNotNull(
            AlarmContract.parse(AlarmContract.intent(context, schedule, AlarmKind.STOP)),
        )
        val firstRetry = PendingIntent.getActivity(
            context,
            AlarmContract.requestCode(AlarmKind.STOP),
            AlarmContract.triggerIntent(context, trigger.copy(deliveryAttempt = 1)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val replacementRetry = PendingIntent.getActivity(
            context,
            AlarmContract.requestCode(AlarmKind.STOP),
            AlarmContract.triggerIntent(context, trigger.copy(deliveryAttempt = 2)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            assertEquals(firstRetry, replacementRetry)
            assertNotEquals(domain, replacementRetry)

            requireNotNull(
                PendingIntent.getActivity(
                    context,
                    AlarmContract.requestCode(AlarmKind.STOP),
                    AlarmContract.deliveryIdentityIntent(context, schedule.id, AlarmKind.STOP),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).cancel()

            assertNull(
                PendingIntent.getActivity(
                    context,
                    AlarmContract.requestCode(AlarmKind.STOP),
                    AlarmContract.deliveryIdentityIntent(context, schedule.id, AlarmKind.STOP),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            assertNotNull(
                PendingIntent.getActivity(
                    context,
                    AlarmContract.requestCode(AlarmKind.STOP),
                    AlarmContract.identityIntent(context, schedule.id, AlarmKind.STOP),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } finally {
            domain.cancel()
            firstRetry.cancel()
            replacementRetry.cancel()
        }
    }

    @Test
    fun parserRejectsKindMismatch() {
        val result = AlarmContract.parse(
            AlarmContract.intent(context, schedule, AlarmKind.START),
            AlarmKind.STOP,
        )

        assertEquals(null, result)
    }

    @Test
    fun gatewayParserInfersStartAndStopKindsFromExplicitActions() {
        assertEquals(
            AlarmKind.START,
            AlarmContract.parse(AlarmContract.intent(context, schedule, AlarmKind.START))?.kind,
        )
        assertEquals(
            AlarmKind.STOP,
            AlarmContract.parse(AlarmContract.intent(context, schedule, AlarmKind.STOP))?.kind,
        )
    }

    private fun legacyServicePendingIntent(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            AlarmWakePendingIntentFactory.legacyServiceIntent(context, intent),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun findLegacyServicePendingIntent(requestCode: Int, intent: Intent): PendingIntent? =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            AlarmWakePendingIntentFactory.legacyServiceIntent(context, intent),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
}

internal fun testSchedule(): RecordingSchedule = RecordingSchedule(
    id = ScheduleId("alarm-contract-schedule"),
    name = "Morning time lapse",
    startAt = Instant.parse("2026-08-10T05:30:00Z"),
    stopAt = Instant.parse("2026-08-10T07:30:00Z"),
    zoneId = ZoneId.of("UTC"),
    capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X30),
    profileId = ProfileId("pixel-profile"),
    enabled = true,
    createdAt = Instant.parse("2026-08-09T10:00:00Z"),
    updatedAt = Instant.parse("2026-08-09T11:00:00Z"),
)
