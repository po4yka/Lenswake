package dev.po4yka.lenswake.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmManagerRecordingSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-08-09T12:00:00Z")
    private val schedule = testSchedule()

    @Test
    fun transientScheduleIsRejectedBeforeExactAlarmCapabilityIsConsulted() = runBlocking {
        val scheduler = scheduler(
            persisted = null,
            capability = ExactAlarmCapability { error("Capability must not be consulted") },
        )

        val failure = scheduler.scheduleStart(schedule).exceptionOrNull() as SchedulingException

        assertEquals(SchedulingFailureCode.SCHEDULE_NOT_PERSISTED, failure.code)
    }

    @Test
    fun staleOrModifiedSnapshotIsRejectedBeforeRegistration() = runBlocking {
        val scheduler = scheduler(
            persisted = schedule,
            capability = ExactAlarmCapability { error("Capability must not be consulted") },
        )

        val failure = scheduler.scheduleStart(schedule.copy(name = "Transient edit"))
            .exceptionOrNull() as SchedulingException

        assertEquals(SchedulingFailureCode.STALE_SCHEDULE_SNAPSHOT, failure.code)
    }

    @Test
    fun exactAlarmDenialFailsClosedWithoutInexactFallback() = runBlocking {
        val scheduler = scheduler(
            persisted = schedule,
            capability = ExactAlarmCapability { false },
        )

        val result = scheduler.scheduleStart(schedule)
        val failure = result.exceptionOrNull() as SchedulingException

        assertTrue(result.isFailure)
        assertEquals(SchedulingFailureCode.EXACT_ALARM_UNAVAILABLE, failure.code)
    }

    private fun scheduler(
        persisted: RecordingSchedule?,
        capability: ExactAlarmCapability,
    ): AlarmManagerRecordingScheduler = AlarmManagerRecordingScheduler(
        context = context,
        scheduleRepository = SingleScheduleRepository(persisted),
        clock = LenswakeClock { now },
        exactAlarmCapability = capability,
    )
}

private class SingleScheduleRepository(
    private val schedule: RecordingSchedule?,
) : ScheduleRepository {
    override fun observeSchedules(): Flow<List<RecordingSchedule>> = flowOf(listOfNotNull(schedule))

    override suspend fun get(id: ScheduleId): RecordingSchedule? = schedule?.takeIf { it.id == id }

    override suspend fun save(schedule: RecordingSchedule) = error("Not used")

    override suspend fun delete(id: ScheduleId) = error("Not used")
}
