package dev.po4yka.lenswake.alarm

import android.app.PendingIntent
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val schedule = testSchedule()

    @Test
    fun startAndStopHaveIndependentPendingIntentIdentities() {
        val start = PendingIntent.getBroadcast(
            context,
            1_001,
            AlarmContract.intent(context, schedule, AlarmKind.START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            1_002,
            AlarmContract.intent(context, schedule, AlarmKind.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            assertNotEquals(start, stop)
        } finally {
            start.cancel()
            stop.cancel()
        }
    }

    @Test
    fun scheduleRevisionUpdatesPayloadWithoutChangingStartIdentity() {
        val initial = PendingIntent.getBroadcast(
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
        val replacement = PendingIntent.getBroadcast(
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
    fun parserRejectsKindMismatch() {
        val result = AlarmContract.parse(
            AlarmContract.intent(context, schedule, AlarmKind.START),
            AlarmKind.STOP,
        )

        assertEquals(null, result)
    }
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
