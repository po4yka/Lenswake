package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleWorkflowTest {
    @Test
    fun createPersistsThenArmsIndependentStartAndStopAlarms() = runTest {
        val fixture = fixture()

        val result = fixture.workflow.create(command())

        val applied = assertInstanceOf(ScheduleWorkflowResult.Applied::class.java, result)
        val capture = applied.schedule.capture as CaptureConfiguration.TimeLapse
        assertEquals(ScheduleOperation.CREATED, applied.operation)
        assertEquals(listOf("save", "start", "stop"), fixture.events)
        assertEquals(TimeLapseSpeed.X120, capture.speed)
        assertEquals(LensSelection.REAR_MAIN, capture.lens)
        assertEquals(profileId, applied.schedule.profileId)
        assertTrue(applied.schedule.enabled)
    }

    @Test
    fun partialStopRegistrationCancelsAlarmAndRemovesNewSchedule() = runTest {
        val fixture = fixture(stopFailures = 1)

        val result = fixture.workflow.create(command())

        val failed = assertInstanceOf(ScheduleWorkflowResult.Failed::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.STOP_ALARM_FAILED, failed.code)
        assertEquals(listOf("save", "start", "stop", "cancel", "delete"), fixture.events)
        assertTrue(failed.rollbackFailures.isEmpty())
        assertTrue(fixture.schedules.current().isEmpty())
    }

    @Test
    fun failedEditRestoresPreviousPersistenceAndBothPreviousAlarms() = runTest {
        val previous = schedule()
        val fixture = fixture(stopFailures = 1, schedules = listOf(previous))

        val result = fixture.workflow.edit(
            previous.id,
            command(name = "Updated", startAt = now.plusSeconds(7_200), stopAt = now.plusSeconds(14_400)),
        )

        val failed = assertInstanceOf(ScheduleWorkflowResult.Failed::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.STOP_ALARM_FAILED, failed.code)
        assertEquals(
            listOf("cancel", "save", "start", "stop", "cancel", "save", "start", "stop"),
            fixture.events,
        )
        assertEquals(previous, fixture.schedules.get(previous.id))
        assertTrue(failed.rollbackFailures.isEmpty())
    }

    @Test
    fun disableCancelsBeforePersistingAndDoesNotArmNewAlarms() = runTest {
        val previous = schedule()
        val fixture = fixture(schedules = listOf(previous))

        val result = fixture.workflow.setEnabled(previous.id, enabled = false)

        val applied = assertInstanceOf(ScheduleWorkflowResult.Applied::class.java, result)
        assertEquals(ScheduleOperation.DISABLED, applied.operation)
        assertEquals(listOf("cancel", "save"), fixture.events)
        assertFalse(fixture.schedules.get(previous.id)!!.enabled)
    }

    @Test
    fun enableRevalidatesFutureTimeAndArmsBothAlarms() = runTest {
        val previous = schedule().copy(enabled = false)
        val fixture = fixture(schedules = listOf(previous))

        val result = fixture.workflow.setEnabled(previous.id, enabled = true)

        val applied = assertInstanceOf(ScheduleWorkflowResult.Applied::class.java, result)
        assertEquals(ScheduleOperation.ENABLED, applied.operation)
        assertEquals(listOf("cancel", "save", "start", "stop"), fixture.events)
        assertTrue(fixture.schedules.get(previous.id)!!.enabled)
    }

    @Test
    fun enableRejectsPastStartWithoutChangingPersistenceOrAlarms() = runTest {
        val previous = schedule().copy(enabled = false, startAt = now.minusSeconds(1))
        val fixture = fixture(schedules = listOf(previous))

        val result = fixture.workflow.setEnabled(previous.id, enabled = true)

        val rejected = assertInstanceOf(ScheduleWorkflowResult.Rejected::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.INVALID_SCHEDULE, rejected.code)
        assertTrue(fixture.events.isEmpty())
        assertEquals(previous, fixture.schedules.get(previous.id))
    }

    @Test
    fun deleteCancelsAlarmsBeforeDeletingPersistence() = runTest {
        val previous = schedule()
        val fixture = fixture(schedules = listOf(previous))

        val result = fixture.workflow.delete(previous.id)

        assertInstanceOf(ScheduleWorkflowResult.Deleted::class.java, result)
        assertEquals(listOf("cancel", "delete"), fixture.events)
        assertNull(fixture.schedules.get(previous.id))
    }

    @Test
    fun missingExactProfileRejectsCreateWithoutPersistence() = runTest {
        val fixture = fixture(profileInstalled = false)

        val result = fixture.workflow.create(command())

        val rejected = assertInstanceOf(ScheduleWorkflowResult.Rejected::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.PROFILE_NOT_FOUND, rejected.code)
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.schedules.current().isEmpty())
    }

    @Test
    fun installedButUnverifiedProfileRejectsCreateWithoutPersistence() = runTest {
        val events = mutableListOf<String>()
        val schedules = FakeScheduleRepository(emptyList(), events)
        val profiles = FakeProfileRepository(
            listOf(profile().copy(compatibility = ProfileCompatibility.NEEDS_REHEARSAL, verifiedAt = null)),
        )
        val workflow = ScheduleWorkflow(
            scheduleRepository = schedules,
            profileRepository = profiles,
            scheduler = FakeRecordingScheduler(events, stopFailures = 0),
            clock = LenswakeClock { now },
            preflightProbe = RuntimePreflightProbe { readyPreflight() },
        )

        val result = workflow.create(command())

        val rejected = assertInstanceOf(ScheduleWorkflowResult.Rejected::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.PROFILE_NOT_VERIFIED, rejected.code)
        assertTrue(events.isEmpty())
        assertTrue(schedules.current().isEmpty())
    }

    @Test
    fun blockedCurrentPreflightRejectsEnabledCreateWithoutPersistenceOrAlarms() = runTest {
        val events = mutableListOf<String>()
        val schedules = FakeScheduleRepository(emptyList(), events)
        val profiles = FakeProfileRepository(listOf(profile()))
        val workflow = ScheduleWorkflow(
            scheduleRepository = schedules,
            profileRepository = profiles,
            scheduler = FakeRecordingScheduler(events, stopFailures = 0),
            clock = LenswakeClock { now },
            preflightProbe = RuntimePreflightProbe {
                PreflightReport(
                    listOf(
                        PreflightCheck(
                            type = PreflightCheckType.ACCESSIBILITY_CONNECTED,
                            severity = PreflightSeverity.BLOCKING,
                            status = PreflightStatus.FAILED,
                            message = "Accessibility is not connected.",
                        ),
                    ),
                )
            },
        )

        val result = workflow.create(command())

        val rejected = assertInstanceOf(ScheduleWorkflowResult.Rejected::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.RUNTIME_NOT_READY, rejected.code)
        assertTrue(rejected.message.contains("Accessibility is not connected"))
        assertTrue(events.isEmpty())
        assertTrue(schedules.current().isEmpty())
    }

    @Test
    fun concurrentMutationsAreSerializedAcrossPersistenceAndAlarmRegistration() = runTest {
        val events = mutableListOf<String>()
        val schedules = FakeScheduleRepository(emptyList(), events)
        val profiles = FakeProfileRepository(listOf(profile()))
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val scheduler = object : RecordingScheduler {
            private var first = true

            override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> {
                events += "start:${schedule.name}"
                if (first) {
                    first = false
                    startEntered.complete(Unit)
                    releaseStart.await()
                }
                return Result.success(Unit)
            }

            override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> {
                events += "stop:${schedule.name}"
                return Result.success(Unit)
            }

            override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = Result.success(Unit)
            override suspend fun restoreAll(): Result<Unit> = Result.success(Unit)
        }
        val workflow = ScheduleWorkflow(
            scheduleRepository = schedules,
            profileRepository = profiles,
            scheduler = scheduler,
            clock = LenswakeClock { now },
            preflightProbe = RuntimePreflightProbe { readyPreflight() },
        )

        val first = async { workflow.create(command(name = "First")) }
        startEntered.await()
        val second = async { workflow.create(command(name = "Second")) }
        yield()

        assertEquals(1, schedules.current().size)
        releaseStart.complete(Unit)
        assertInstanceOf(ScheduleWorkflowResult.Applied::class.java, first.await())
        assertInstanceOf(ScheduleWorkflowResult.Applied::class.java, second.await())
        assertEquals(
            listOf("save", "start:First", "stop:First", "save", "start:Second", "stop:Second"),
            events,
        )
    }

    @Test
    fun recoveryWaitsForTheWholeScheduleMutationCriticalSection() = runTest {
        val events = mutableListOf<String>()
        val schedules = FakeScheduleRepository(emptyList(), events)
        val profiles = FakeProfileRepository(listOf(profile()))
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val rawScheduler = object : RecordingScheduler {
            override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> {
                events += "start"
                startEntered.complete(Unit)
                releaseStart.await()
                return Result.success(Unit)
            }

            override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> {
                events += "stop"
                return Result.success(Unit)
            }

            override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = Result.success(Unit)

            override suspend fun restoreAll(): Result<Unit> {
                events += "restore"
                return Result.success(Unit)
            }
        }
        val sharedMutex = Mutex()
        val workflow = ScheduleWorkflow(
            scheduleRepository = schedules,
            profileRepository = profiles,
            scheduler = rawScheduler,
            clock = LenswakeClock { now },
            preflightProbe = RuntimePreflightProbe { readyPreflight() },
            mutationMutex = sharedMutex,
        )
        val recoveryScheduler = MutexRecordingScheduler(rawScheduler, sharedMutex)

        val mutation = async { workflow.create(command()) }
        startEntered.await()
        val recovery = async { recoveryScheduler.restoreAll() }
        yield()

        assertFalse(events.contains("restore"))
        releaseStart.complete(Unit)
        assertInstanceOf(ScheduleWorkflowResult.Applied::class.java, mutation.await())
        assertTrue(recovery.await().isSuccess)
        assertEquals(listOf("save", "start", "stop", "restore"), events)
    }

    private fun fixture(
        stopFailures: Int = 0,
        schedules: List<RecordingSchedule> = emptyList(),
        profileInstalled: Boolean = true,
    ): Fixture {
        val events = mutableListOf<String>()
        val scheduleRepository = FakeScheduleRepository(schedules, events)
        val scheduler = FakeRecordingScheduler(events, stopFailures)
        val profiles = FakeProfileRepository(if (profileInstalled) listOf(profile()) else emptyList())
        return Fixture(
            workflow = ScheduleWorkflow(
                scheduleRepository = scheduleRepository,
                profileRepository = profiles,
                scheduler = scheduler,
                clock = LenswakeClock { now },
                preflightProbe = RuntimePreflightProbe { readyPreflight() },
            ),
            schedules = scheduleRepository,
            events = events,
        )
    }

    private data class Fixture(
        val workflow: ScheduleWorkflow,
        val schedules: FakeScheduleRepository,
        val events: MutableList<String>,
    )

    private class FakeScheduleRepository(
        initial: List<RecordingSchedule>,
        private val events: MutableList<String>,
    ) : ScheduleRepository {
        private val schedules = MutableStateFlow(initial)

        override fun observeSchedules(): Flow<List<RecordingSchedule>> = schedules

        override suspend fun get(id: ScheduleId): RecordingSchedule? =
            schedules.value.firstOrNull { it.id == id }

        override suspend fun save(schedule: RecordingSchedule) {
            events += "save"
            schedules.value = schedules.value.filterNot { it.id == schedule.id } + schedule
        }

        override suspend fun delete(id: ScheduleId) {
            events += "delete"
            schedules.value = schedules.value.filterNot { it.id == id }
        }

        fun current(): List<RecordingSchedule> = schedules.value
    }

    private class FakeProfileRepository(
        profiles: List<PixelCameraProfile>,
    ) : AutomationProfileRepository {
        private val profiles = MutableStateFlow(profiles)

        override fun observeProfiles(): Flow<List<PixelCameraProfile>> = profiles
        override suspend fun get(id: ProfileId): PixelCameraProfile? = profiles.value.firstOrNull { it.id == id }
        override suspend fun save(profile: PixelCameraProfile) = error("Not used")
        override suspend fun delete(id: ProfileId) = error("Not used")
    }

    private class FakeRecordingScheduler(
        private val events: MutableList<String>,
        stopFailures: Int,
    ) : RecordingScheduler {
        private var remainingStopFailures = stopFailures

        override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> {
            events += "start"
            return Result.success(Unit)
        }

        override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> {
            events += "stop"
            return if (remainingStopFailures-- > 0) {
                Result.failure(IllegalStateException("stop rejected"))
            } else {
                Result.success(Unit)
            }
        }

        override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> {
            events += "cancel"
            return Result.success(Unit)
        }

        override suspend fun restoreAll(): Result<Unit> = error("Not used")
    }

    private companion object {
        val now: Instant = Instant.parse("2030-01-01T10:00:00Z")
        val profileId = ProfileId("profile-exact")
        val scheduleId = ScheduleId("schedule-1")

        fun command(
            name: String = "Morning capture",
            startAt: Instant = now.plusSeconds(3_600),
            stopAt: Instant = now.plusSeconds(10_800),
        ) = ScheduleCommand(
            name = name,
            startAt = startAt,
            stopAt = stopAt,
            zoneId = ZoneId.of("UTC"),
            profileId = profileId,
            enabled = true,
        )

        fun schedule() = RecordingSchedule(
            id = scheduleId,
            name = "Morning capture",
            startAt = now.plusSeconds(3_600),
            stopAt = now.plusSeconds(10_800),
            zoneId = ZoneId.of("UTC"),
            capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120, LensSelection.REAR_MAIN),
            profileId = profileId,
            enabled = true,
            createdAt = now.minusSeconds(100),
            updatedAt = now.minusSeconds(100),
        )

        fun profile() = PixelCameraProfile(
            id = profileId,
            environment = PixelCameraEnvironment(
                deviceManufacturer = "Google",
                deviceModel = "Pixel 8 Pro",
                androidSdk = 37,
                androidBuildFingerprint = "fingerprint",
                cameraPackage = "com.google.android.GoogleCamera",
                cameraVersionCode = 1,
                localeTag = "en-US",
                displayWidthPx = 1344,
                displayHeightPx = 2992,
                densityDpi = 480,
            ),
            selectorSchemaVersion = 1,
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = now.minusSeconds(60),
        )

        fun readyPreflight() = PreflightReport(
            listOf(
                PreflightCheckType.EXACT_ALARMS,
                PreflightCheckType.PIXEL_CAMERA_INSTALLED,
                PreflightCheckType.SECURE_CAMERA_RESOLVES,
                PreflightCheckType.DEVICE_WAKE,
                PreflightCheckType.ACCESSIBILITY_ENABLED,
                PreflightCheckType.ACCESSIBILITY_CONNECTED,
                PreflightCheckType.PROFILE_AVAILABLE,
                PreflightCheckType.PROFILE_COMPATIBILITY,
                PreflightCheckType.REHEARSAL_CURRENT,
            ).map { type ->
                PreflightCheck(
                    type = type,
                    severity = PreflightSeverity.BLOCKING,
                    status = PreflightStatus.PASSED,
                    message = "$type passed.",
                )
            },
        )
    }
}
