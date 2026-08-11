package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
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
import dev.po4yka.lenswake.core.SessionId
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
        assertEquals(listOf("save", "stop", "start", "save"), fixture.events)
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
        assertEquals(listOf("save", "stop", "cancel", "delete"), fixture.events)
        assertTrue(failed.rollbackFailures.isEmpty())
        assertTrue(fixture.schedules.current().isEmpty())
    }

    @Test
    fun startRegistrationFailureCancelsAlreadyArmedStopAndRemovesNewSchedule() = runTest {
        val fixture = fixture(startFailures = 1)

        val result = fixture.workflow.create(command())

        val failed = assertInstanceOf(ScheduleWorkflowResult.Failed::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.START_ALARM_FAILED, failed.code)
        assertEquals(listOf("save", "stop", "start", "cancel", "delete"), fixture.events)
        assertTrue(failed.rollbackFailures.isEmpty())
        assertTrue(fixture.schedules.current().isEmpty())
    }

    @Test
    fun failedNewScheduleCleanupLeavesPersistedCandidateDisabledWhenDeleteFails() = runTest {
        val fixture = fixture(stopFailures = 1, deleteFailures = 1)

        val result = fixture.workflow.create(command())

        val failed = assertInstanceOf(ScheduleWorkflowResult.Failed::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.STOP_ALARM_FAILED, failed.code)
        assertTrue(failed.rollbackFailures.any { it.contains("rolled-back schedule") })
        assertFalse(fixture.schedules.current().single().enabled)
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
            listOf("save", "cancel", "save", "stop", "cancel", "save", "stop", "start", "save"),
            fixture.events,
        )
        assertEquals(previous, fixture.schedules.get(previous.id))
        assertTrue(failed.rollbackFailures.isEmpty())
    }

    @Test
    fun failedRollbackPersistsPreviousScheduleDisabledWhenStopCannotBeRestored() = runTest {
        val previous = schedule()
        val fixture = fixture(stopFailures = 2, schedules = listOf(previous))

        val result = fixture.workflow.edit(
            previous.id,
            command(name = "Updated", startAt = now.plusSeconds(7_200), stopAt = now.plusSeconds(14_400)),
        )

        val failed = assertInstanceOf(ScheduleWorkflowResult.Failed::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.STOP_ALARM_FAILED, failed.code)
        assertTrue(failed.rollbackFailures.any { it.contains("previous STOP alarm") })
        assertEquals(
            listOf("save", "cancel", "save", "stop", "cancel", "save", "stop", "cancel"),
            fixture.events,
        )
        assertFalse(fixture.schedules.get(previous.id)!!.enabled)
    }

    @Test
    fun failedRollbackReactivationLeavesPreviousScheduleDurablyDisabled() = runTest {
        val previous = schedule()
        val fixture = fixture(
            stopFailures = 1,
            saveFailureCalls = setOf(4),
            schedules = listOf(previous),
        )

        val result = fixture.workflow.edit(
            previous.id,
            command(name = "Updated", startAt = now.plusSeconds(7_200), stopAt = now.plusSeconds(14_400)),
        )

        val failed = assertInstanceOf(ScheduleWorkflowResult.Failed::class.java, result)
        assertTrue(failed.rollbackFailures.any { it.contains("reactivate the previous schedule") })
        assertFalse(fixture.schedules.get(previous.id)!!.enabled)
    }

    @Test
    fun mutationDoesNotTouchAlarmsWhenFailClosedPersistenceFails() = runTest {
        val previous = schedule()
        val fixture = fixture(
            saveFailureCalls = setOf(1),
            schedules = listOf(previous),
        )

        val result = fixture.workflow.edit(
            previous.id,
            command(name = "Updated", startAt = now.plusSeconds(7_200), stopAt = now.plusSeconds(14_400)),
        )

        val failed = assertInstanceOf(ScheduleWorkflowResult.Failed::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.PERSIST_FAILED, failed.code)
        assertEquals(listOf("save"), fixture.events)
        assertEquals(previous, fixture.schedules.get(previous.id))
    }

    @Test
    fun disableCancelsBeforePersistingAndDoesNotArmNewAlarms() = runTest {
        val previous = schedule()
        val fixture = fixture(schedules = listOf(previous))

        val result = fixture.workflow.setEnabled(previous.id, enabled = false)

        val applied = assertInstanceOf(ScheduleWorkflowResult.Applied::class.java, result)
        assertEquals(ScheduleOperation.DISABLED, applied.operation)
        assertEquals(listOf("save", "cancel", "save"), fixture.events)
        assertFalse(fixture.schedules.get(previous.id)!!.enabled)
    }

    @Test
    fun enableRevalidatesFutureTimeAndArmsBothAlarms() = runTest {
        val previous = schedule().copy(enabled = false)
        val fixture = fixture(schedules = listOf(previous))

        val result = fixture.workflow.setEnabled(previous.id, enabled = true)

        val applied = assertInstanceOf(ScheduleWorkflowResult.Applied::class.java, result)
        assertEquals(ScheduleOperation.ENABLED, applied.operation)
        assertEquals(listOf("cancel", "save", "stop", "start", "save"), fixture.events)
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
    fun deletePersistsDisabledThenCancelsAlarmsBeforeDeletingPersistence() = runTest {
        val previous = schedule()
        val fixture = fixture(schedules = listOf(previous))

        val result = fixture.workflow.delete(previous.id)

        assertInstanceOf(ScheduleWorkflowResult.Deleted::class.java, result)
        assertEquals(listOf("save", "cancel", "delete"), fixture.events)
        assertNull(fixture.schedules.get(previous.id))
    }

    @Test
    fun expiredScheduleWithoutPixelCameraOwnershipCanBeDeleted() = runTest {
        val previous = schedule().copy(startAt = now.minusSeconds(60), stopAt = now.minusSeconds(1))
        val fixture = fixture(schedules = listOf(previous))

        val result = fixture.workflow.delete(previous.id)

        assertInstanceOf(ScheduleWorkflowResult.Deleted::class.java, result)
        assertEquals(listOf("save", "cancel", "delete"), fixture.events)
    }

    @Test
    fun pixelCameraOwnerBlocksMutationBeforeSchedulerOrPersistenceChanges() = runTest {
        val previous = schedule()
        val fixture = fixture(schedules = listOf(previous), owner = owningSession(previous))

        val result = fixture.workflow.delete(previous.id)

        val rejected = assertInstanceOf(ScheduleWorkflowResult.Rejected::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.SCHEDULE_EXECUTION_ACTIVE, rejected.code)
        assertTrue(fixture.events.isEmpty())
        assertEquals(previous, fixture.schedules.get(previous.id))
    }

    @Test
    fun terminalAndReleasedExecutionsDoNotBlockExpiredScheduleMutation() = runTest {
        val previous = schedule().copy(startAt = now.minusSeconds(60), stopAt = now.minusSeconds(1))
        val terminal = owningSession(previous).copy(status = dev.po4yka.lenswake.core.SessionStatus.FAILED, recordActionAt = null)
        val released = owningSession(previous).copy(cameraOwnershipReleasedAt = now)

        listOf(terminal, released).forEach { execution ->
            val fixture = fixture(schedules = listOf(previous), owner = execution)
            assertInstanceOf(ScheduleWorkflowResult.Deleted::class.java, fixture.workflow.delete(previous.id))
            assertEquals(listOf("save", "cancel", "delete"), fixture.events)
        }
    }

    @Test
    fun unavailableExecutionStateFailsClosedBeforeSchedulerOrPersistenceChanges() = runTest {
        val previous = schedule()
        val fixture = fixture(schedules = listOf(previous), ownerQueryFailure = IllegalStateException("database unavailable"))

        val result = fixture.workflow.setEnabled(previous.id, enabled = false)

        val failed = assertInstanceOf(ScheduleWorkflowResult.Failed::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.EXECUTION_STATE_UNAVAILABLE, failed.code)
        assertTrue(fixture.events.isEmpty())
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
            executionRepository = FakeExecutionRepository(),
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
            executionRepository = FakeExecutionRepository(),
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
    fun missingSavedVideoAccessRejectsEnabledCreateWithoutPersistenceOrAlarms() = runTest {
        val events = mutableListOf<String>()
        val schedules = FakeScheduleRepository(emptyList(), events)
        val profiles = FakeProfileRepository(listOf(profile()))
        val workflow = ScheduleWorkflow(
            scheduleRepository = schedules,
            executionRepository = FakeExecutionRepository(),
            profileRepository = profiles,
            scheduler = FakeRecordingScheduler(events, stopFailures = 0),
            clock = LenswakeClock { now },
            preflightProbe = RuntimePreflightProbe {
                readyPreflight().copy(
                    checks = readyPreflight().checks.map { check ->
                        if (check.type == PreflightCheckType.MEDIA_VIDEO_ACCESS) {
                            check.copy(
                                status = PreflightStatus.FAILED,
                                message = "Saved video access is not granted.",
                            )
                        } else {
                            check
                        }
                    },
                )
            },
        )

        val result = workflow.create(command())

        val rejected = assertInstanceOf(ScheduleWorkflowResult.Rejected::class.java, result)
        assertEquals(ScheduleWorkflowFailureCode.RUNTIME_NOT_READY, rejected.code)
        assertTrue(rejected.message.contains("Saved video access is not granted"))
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

            override suspend fun stageStart(schedule: RecordingSchedule): Result<Unit> = scheduleStart(schedule)

            override suspend fun stageStop(schedule: RecordingSchedule): Result<Unit> = scheduleStop(schedule)

            override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = Result.success(Unit)
            override suspend fun restoreAll(): Result<Unit> = Result.success(Unit)
        }
        val workflow = ScheduleWorkflow(
            scheduleRepository = schedules,
            executionRepository = FakeExecutionRepository(),
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
            listOf(
                "save", "stop:First", "start:First", "save",
                "save", "stop:Second", "start:Second", "save",
            ),
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

            override suspend fun stageStart(schedule: RecordingSchedule): Result<Unit> = scheduleStart(schedule)

            override suspend fun stageStop(schedule: RecordingSchedule): Result<Unit> = scheduleStop(schedule)

            override suspend fun cancel(scheduleId: ScheduleId): Result<Unit> = Result.success(Unit)

            override suspend fun restoreAll(): Result<Unit> {
                events += "restore"
                return Result.success(Unit)
            }
        }
        val sharedMutex = Mutex()
        val workflow = ScheduleWorkflow(
            scheduleRepository = schedules,
            executionRepository = FakeExecutionRepository(),
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
        assertEquals(listOf("save", "stop", "start", "save", "restore"), events)
    }

    private fun fixture(
        startFailures: Int = 0,
        stopFailures: Int = 0,
        saveFailureCalls: Set<Int> = emptySet(),
        deleteFailures: Int = 0,
        schedules: List<RecordingSchedule> = emptyList(),
        profileInstalled: Boolean = true,
        owner: ExecutionSession? = null,
        ownerQueryFailure: Exception? = null,
    ): Fixture {
        val events = mutableListOf<String>()
        val scheduleRepository = FakeScheduleRepository(
            initial = schedules,
            events = events,
            saveFailureCalls = saveFailureCalls,
            deleteFailures = deleteFailures,
        )
        val scheduler = FakeRecordingScheduler(events, startFailures, stopFailures)
        val profiles = FakeProfileRepository(if (profileInstalled) listOf(profile()) else emptyList())
        return Fixture(
            workflow = ScheduleWorkflow(
                scheduleRepository = scheduleRepository,
                executionRepository = FakeExecutionRepository(owner, ownerQueryFailure),
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
        private val saveFailureCalls: Set<Int> = emptySet(),
        deleteFailures: Int = 0,
    ) : ScheduleRepository {
        private val schedules = MutableStateFlow(initial)
        private var saveCalls = 0
        private var remainingDeleteFailures = deleteFailures

        override fun observeSchedules(): Flow<List<RecordingSchedule>> = schedules

        override suspend fun get(id: ScheduleId): RecordingSchedule? =
            schedules.value.firstOrNull { it.id == id }

        override suspend fun save(schedule: RecordingSchedule) {
            events += "save"
            saveCalls += 1
            if (saveCalls in saveFailureCalls) throw IllegalStateException("save rejected")
            schedules.value = schedules.value.filterNot { it.id == schedule.id } + schedule
        }

        override suspend fun delete(id: ScheduleId) {
            events += "delete"
            if (remainingDeleteFailures-- > 0) throw IllegalStateException("delete rejected")
            schedules.value = schedules.value.filterNot { it.id == id }
        }

        fun current(): List<RecordingSchedule> = schedules.value
    }

    private class FakeProfileRepository(
        profiles: List<PixelCameraProfile>,
    ) : AutomationProfileRepository {
        private val profiles = MutableStateFlow(profiles)

        override fun observeProfiles(): Flow<List<PixelCameraProfile>> = profiles
        override fun observePersistenceIssues(): Flow<List<dev.po4yka.lenswake.core.ProfilePersistenceIssue>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun get(id: ProfileId): PixelCameraProfile? = profiles.value.firstOrNull { it.id == id }
        override suspend fun save(profile: PixelCameraProfile) = error("Not used")
        override suspend fun delete(id: ProfileId) = error("Not used")
    }

    private class FakeExecutionRepository(
        private val owner: ExecutionSession? = null,
        private val queryFailure: Exception? = null,
    ) : ExecutionRepository {
        override fun observeExecutions(): Flow<List<ExecutionSession>> = MutableStateFlow(emptyList())
        override fun observeExecution(id: SessionId): Flow<ExecutionSession?> = MutableStateFlow(null)
        override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> = MutableStateFlow(emptyList())
        override suspend fun get(id: SessionId): ExecutionSession? = null
        override suspend fun findPixelCameraOwnerForSchedule(scheduleId: ScheduleId): ExecutionSession? {
            queryFailure?.let { throw it }
            return owner?.takeIf { it.scheduleId == scheduleId && it.ownsPixelCamera }
        }
        override suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult =
            error("Not used by ScheduleWorkflow tests")
        override suspend fun apply(change: ExecutionChange, event: AutomationEvent): ExecutionApplyResult =
            error("Not used by ScheduleWorkflow tests")
    }

    private class FakeRecordingScheduler(
        private val events: MutableList<String>,
        startFailures: Int = 0,
        stopFailures: Int = 0,
    ) : RecordingScheduler {
        private var remainingStartFailures = startFailures
        private var remainingStopFailures = stopFailures

        override suspend fun scheduleStart(schedule: RecordingSchedule): Result<Unit> {
            events += "start"
            return if (remainingStartFailures-- > 0) {
                Result.failure(IllegalStateException("start rejected"))
            } else {
                Result.success(Unit)
            }
        }

        override suspend fun scheduleStop(schedule: RecordingSchedule): Result<Unit> {
            events += "stop"
            return if (remainingStopFailures-- > 0) {
                Result.failure(IllegalStateException("stop rejected"))
            } else {
                Result.success(Unit)
            }
        }

        override suspend fun stageStart(schedule: RecordingSchedule): Result<Unit> = scheduleStart(schedule)

        override suspend fun stageStop(schedule: RecordingSchedule): Result<Unit> = scheduleStop(schedule)

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

        fun owningSession(schedule: RecordingSchedule) = ExecutionSession(
            id = SessionId("session-${schedule.id.value}"),
            executionKey = "schedule/${schedule.id.value}/owner",
            kind = dev.po4yka.lenswake.core.SessionKind.SCHEDULED,
            scheduleId = schedule.id,
            scheduleName = schedule.name,
            profileId = schedule.profileId,
            capture = schedule.capture,
            expectedStartAt = schedule.startAt.minusSeconds(1),
            expectedStopAt = schedule.stopAt.plusSeconds(1),
            status = dev.po4yka.lenswake.core.SessionStatus.RECORDING,
            recordActionAt = schedule.startAt,
            createdAt = schedule.createdAt,
            updatedAt = schedule.updatedAt,
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
                PreflightCheckType.NOTIFICATIONS,
                PreflightCheckType.MEDIA_VIDEO_ACCESS,
                PreflightCheckType.FULL_SCREEN_INTENT,
                PreflightCheckType.PIXEL_CAMERA_INSTALLED,
                PreflightCheckType.SECURE_CAMERA_RESOLVES,
                PreflightCheckType.DEVICE_WAKE,
                PreflightCheckType.ACCESSIBILITY_ENABLED,
                PreflightCheckType.ACCESSIBILITY_CONNECTED,
                PreflightCheckType.PROFILE_AVAILABLE,
                PreflightCheckType.PROFILE_COMPATIBILITY,
                PreflightCheckType.REHEARSAL_CURRENT,
                PreflightCheckType.BATTERY,
                PreflightCheckType.CHARGING,
                PreflightCheckType.STORAGE,
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
