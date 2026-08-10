package dev.po4yka.lenswake.alarm

import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmManagerRecordingRecoveryTest {
    private val now = Instant.parse("2026-08-10T10:00:00Z")

    @Test
    fun futureStartAndStopAreReplacedStopFirstWithoutCancellationGap() = runBlocking {
        val schedule = schedule(start = now.plusSeconds(60), stop = now.plusSeconds(120))
        val backend = RecordingAlarmBackendSpy()

        val result = scheduler(listOf(schedule), backend).restoreAll()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("arm:STOP", "arm:START"),
            backend.operations,
        )
    }

    @Test
    fun pastStartIsCancelledOnlyAfterFutureStopIsReplaced() = runBlocking {
        val schedule = schedule(start = now.minusSeconds(60), stop = now.plusSeconds(120))
        val backend = RecordingAlarmBackendSpy()

        assertTrue(scheduler(listOf(schedule), backend).restoreAll().isSuccess)

        assertEquals(listOf("arm:STOP", "cancel:START"), backend.operations)
    }

    @Test
    fun disabledAndExpiredSchedulesCancelBothIdentities() = runBlocking {
        val disabled = schedule(
            id = "disabled",
            start = now.plusSeconds(60),
            stop = now.plusSeconds(120),
            enabled = false,
        )
        val expired = schedule(
            id = "expired",
            start = now.minusSeconds(120),
            stop = now.minusSeconds(60),
        )
        val backend = RecordingAlarmBackendSpy()

        assertTrue(scheduler(listOf(disabled, expired), backend).restoreAll().isSuccess)

        assertEquals(
            listOf(
                "cancel:START",
                "cancel:STOP",
                "cancel:START",
                "cancel:STOP",
            ),
            backend.operations,
        )
    }

    @Test
    fun overdueStopForActiveOwnerIsRearmedImmediatelyBeforeStartCancellation() = runBlocking {
        val overdue = schedule(
            id = "overdue-owner",
            start = now.minusSeconds(120),
            stop = now.minusSeconds(60),
        )
        val backend = RecordingAlarmBackendSpy()

        assertTrue(
            scheduler(
                schedules = listOf(overdue),
                backend = backend,
                ownerScheduleIds = setOf(overdue.id),
            ).restoreAll().isSuccess,
        )

        assertEquals(listOf("arm:STOP", "cancel:START"), backend.operations)
        assertEquals(now.plusMillis(1_000), backend.armed.single().third)
    }

    @Test
    fun disabledScheduleWithActiveOwnerRetainsItsAuthoritativeStop() = runBlocking {
        val disabled = schedule(
            id = "disabled-owner",
            start = now.minusSeconds(120),
            stop = now.minusSeconds(60),
            enabled = false,
        )
        val backend = RecordingAlarmBackendSpy()

        assertTrue(
            scheduler(
                schedules = listOf(disabled),
                backend = backend,
                ownerScheduleIds = setOf(disabled.id),
            ).restoreAll().isSuccess,
        )

        assertEquals(listOf("arm:STOP", "cancel:START"), backend.operations)
        assertEquals(now.plusMillis(1_000), backend.armed.single().third)
    }

    @Test
    fun ownerLookupFailureLeavesExistingAlarmIdentitiesUntouched() = runBlocking {
        val overdue = schedule(
            id = "lookup-failure",
            start = now.minusSeconds(120),
            stop = now.minusSeconds(60),
        )
        val backend = RecordingAlarmBackendSpy()

        val result = scheduler(
            schedules = listOf(overdue),
            backend = backend,
            ownerLookupFailure = IllegalStateException("database unavailable"),
        ).restoreAll()

        assertTrue(result.isFailure)
        assertTrue(backend.operations.isEmpty())
    }

    @Test
    fun partialReplacementFailureDoesNotCancelPreviouslyInstalledIdentities() = runBlocking {
        val schedule = schedule(start = now.plusSeconds(60), stop = now.plusSeconds(120))
        val backend = RecordingAlarmBackendSpy(failArmKind = AlarmKind.START)

        val result = scheduler(listOf(schedule), backend).restoreAll()

        assertTrue(result.isFailure)
        assertEquals(listOf("arm:STOP", "arm:START"), backend.operations)
    }

    @Test
    fun staleObservedScheduleDoesNotTouchIdentitiesAfterConcurrentMutation() = runBlocking {
        val observed = schedule(start = now.plusSeconds(60), stop = now.plusSeconds(120))
        val current = observed.copy(enabled = false, updatedAt = now)
        val backend = RecordingAlarmBackendSpy()
        val scheduler = AlarmManagerRecordingScheduler(
            scheduleRepository = RecoveryScheduleRepository(listOf(observed), current),
            clock = { now },
            backend = backend,
            hasPixelCameraOwner = { false },
        )

        assertTrue(scheduler.restoreAll().isSuccess)

        assertTrue(backend.operations.isEmpty())
    }

    private fun scheduler(
        schedules: List<RecordingSchedule>,
        backend: RecordingAlarmBackend,
        ownerScheduleIds: Set<ScheduleId> = emptySet(),
        ownerLookupFailure: Throwable? = null,
    ) = AlarmManagerRecordingScheduler(
        scheduleRepository = RecoveryScheduleRepository(schedules),
        clock = { now },
        backend = backend,
        hasPixelCameraOwner = { scheduleId ->
            ownerLookupFailure?.let { throw it }
            scheduleId in ownerScheduleIds
        },
    )

    private fun schedule(
        id: String = "schedule",
        start: Instant,
        stop: Instant,
        enabled: Boolean = true,
    ) = RecordingSchedule(
        id = ScheduleId(id),
        name = id,
        startAt = start,
        stopAt = stop,
        zoneId = ZoneId.of("UTC"),
        capture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X120,
            lens = LensSelection.REAR_MAIN,
        ),
        profileId = ProfileId("profile"),
        enabled = enabled,
        createdAt = now.minusSeconds(300),
        updatedAt = now.minusSeconds(30),
    )
}

private class RecordingAlarmBackendSpy(
    private val failArmKind: AlarmKind? = null,
) : RecordingAlarmBackend {
    val operations = mutableListOf<String>()
    val armed = mutableListOf<Triple<ScheduleId, AlarmKind, Instant>>()

    override fun canScheduleExactAlarms(): Boolean = true

    override fun arm(schedule: RecordingSchedule, kind: AlarmKind, triggerAt: Instant) {
        operations += "arm:${kind.name}"
        armed += Triple(schedule.id, kind, triggerAt)
        if (kind == failArmKind) error("arm ${kind.name} failed")
    }

    override fun cancel(scheduleId: ScheduleId, kind: AlarmKind) {
        operations += "cancel:${kind.name}"
    }
}

private class RecoveryScheduleRepository(
    private val schedules: List<RecordingSchedule>,
    private val current: RecordingSchedule? = null,
) : ScheduleRepository {
    override fun observeSchedules(): Flow<List<RecordingSchedule>> = flowOf(schedules)

    override suspend fun get(id: ScheduleId): RecordingSchedule? =
        current?.takeIf { it.id == id } ?: schedules.singleOrNull { it.id == id }

    override suspend fun save(schedule: RecordingSchedule) = error("Not used")

    override suspend fun delete(id: ScheduleId) = error("Not used")
}
