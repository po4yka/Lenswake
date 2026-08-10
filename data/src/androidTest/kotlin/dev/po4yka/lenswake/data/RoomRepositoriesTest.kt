package dev.po4yka.lenswake.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.EnvironmentCapabilityStatus
import dev.po4yka.lenswake.core.EnvironmentSnapshot
import dev.po4yka.lenswake.core.EnvironmentSnapshotCaptureResult
import dev.po4yka.lenswake.core.EnvironmentSnapshotId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.GestureProfile
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.NormalizedPoint
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import dev.po4yka.lenswake.data.internal.mapping.JsonColumnCodec
import dev.po4yka.lenswake.data.internal.mapping.toEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRepositoriesTest {
    private lateinit var database: LenswakeDatabase
    private lateinit var profiles: RoomAutomationProfileRepository
    private lateinit var schedules: RoomScheduleRepository
    private lateinit var executions: RoomExecutionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LenswakeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        profiles = RoomAutomationProfileRepository(database)
        schedules = RoomScheduleRepository(database)
        executions = RoomExecutionRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roundTripsScheduleAndProfileWithoutAndroidTypesInDomain() = runBlocking {
        val profile = profile()
        val schedule = schedule(profile.id)

        profiles.save(profile)
        schedules.save(schedule)

        val restoredProfile = profiles.get(profile.id)
        assertEquals(profile, restoredProfile)
        assertEquals(
            profile.targets[AutomationAction.SELECT_REAR_MAIN_LENS],
            restoredProfile?.targets?.get(AutomationAction.SELECT_REAR_MAIN_LENS),
        )
        assertEquals(
            profile.stateSignals[PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE],
            restoredProfile?.stateSignals?.get(PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE),
        )
        assertEquals(
            true,
            restoredProfile
                ?.stateSignals
                ?.get(PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE)
                ?.selectors
                ?.single()
                ?.expectedChecked,
        )
        assertEquals(
            profile.speedTargets[TimeLapseSpeed.X120],
            restoredProfile?.speedTargets?.get(TimeLapseSpeed.X120),
        )
        assertEquals(
            true,
            restoredProfile
                ?.stateSignals
                ?.get(PixelCameraStateSignal.RECORDING_ACTIVE)
                ?.selectors
                ?.single()
                ?.expectedSelected,
        )
        assertEquals(schedule, schedules.get(schedule.id))
        assertEquals(listOf(schedule), schedules.observeSchedules().first())
    }

    @Test
    fun previousProfileSelectorJsonSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonColumnCodec.decodeTargets("""{"schemaVersion":1,"targets":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonColumnCodec.decodeSpeedTargets("""{"schemaVersion":1,"targets":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonColumnCodec.decodeStateSignals("""{"schemaVersion":1,"signals":[]}""")
        }
    }

    @Test
    fun profileRestrictionDoesNotTieDiagnosticHistoryToDeletedConfiguration() = runBlocking {
        val profile = profile()
        val schedule = schedule(profile.id)
        val session = session(schedule, profile.id)
        profiles.save(profile)
        schedules.save(schedule)
        assertEquals(
            ExecutionReservationResult.Reserved(session, newlyCreated = true),
            executions.reservePixelCamera(session),
        )

        val restrictionObserved = runCatching { profiles.delete(profile.id) }.isFailure
        assertTrue("A profile referenced by a schedule must be RESTRICTed", restrictionObserved)

        val updated = session.copy(
            status = SessionStatus.STARTING,
            currentAutomationState = AutomationStateName.START_TRIGGERED,
            revision = 1,
            updatedAt = Instant.ofEpochMilli(5_000),
        )
        val event = event(session.id, "automation.start.triggered")
        assertEquals(
            ExecutionApplyResult.Applied(updated),
            executions.apply(ExecutionChange(0, updated), event),
        )

        schedules.delete(schedule.id)
        profiles.delete(profile.id)

        assertNull(schedules.get(schedule.id))
        assertNull(profiles.get(profile.id))
        assertEquals(updated, executions.get(session.id))
        assertEquals(listOf(event.copy(sequence = 0)), executions.observeEvents(session.id).first())
    }

    @Test
    fun compareAndSetConflictPersistsNeitherSessionNorEvent() = runBlocking {
        val profile = profile()
        val schedule = schedule(profile.id)
        val session = session(schedule, profile.id)
        profiles.save(profile)
        schedules.save(schedule)
        assertEquals(
            ExecutionReservationResult.Reserved(session, newlyCreated = true),
            executions.reservePixelCamera(session),
        )
        assertEquals(
            ExecutionReservationResult.Reserved(session, newlyCreated = false),
            executions.reservePixelCamera(session),
        )

        val rejected = session.copy(
            status = SessionStatus.RECORDING,
            currentAutomationState = AutomationStateName.RECORDING,
            revision = 2,
            updatedAt = Instant.ofEpochMilli(6_000),
        )
        val result = executions.apply(
            ExecutionChange(expectedRevision = 1, updatedSession = rejected),
            event(session.id, "automation.record.start_verified"),
        )

        assertEquals(ExecutionApplyResult.RevisionConflict(1, 0), result)
        assertEquals(session, executions.get(session.id))
        assertTrue(executions.observeEvents(session.id).first().isEmpty())
        assertNotNull(executions.findActiveForSchedule(schedule.id))
    }

    @Test
    fun eventInsertFailureRollsBackSessionUpdate() = runBlocking {
        val profile = profile()
        val schedule = schedule(profile.id)
        val session = session(schedule, profile.id)
        profiles.save(profile)
        schedules.save(schedule)
        executions.reservePixelCamera(session)

        val revisionOne = session.copy(
            status = SessionStatus.STARTING,
            revision = 1,
            updatedAt = Instant.ofEpochMilli(5_000),
        )
        val event = event(session.id, "automation.start.triggered")
        executions.apply(ExecutionChange(0, revisionOne), event)

        val revisionTwo = revisionOne.copy(
            currentAutomationState = AutomationStateName.CHECKING_PREREQUISITES,
            revision = 2,
            updatedAt = Instant.ofEpochMilli(6_000),
        )
        val insertFailed = runCatching {
            executions.apply(
                ExecutionChange(1, revisionTwo),
                event.copy(name = "automation.preflight.started"),
            )
        }.isFailure

        assertTrue("A duplicate event id must fail the transaction", insertFailed)
        assertEquals(revisionOne, executions.get(session.id))
        assertEquals(listOf(event.copy(sequence = 0)), executions.observeEvents(session.id).first())
    }

    @Test
    fun environmentSnapshotRoundTripsAndAppearsInExecutionReport() = runBlocking {
        val profile = profile()
        val schedule = schedule(profile.id)
        val session = session(schedule, profile.id)
        val snapshot = environmentSnapshot(session.id)
        profiles.save(profile)
        schedules.save(schedule)
        executions.reservePixelCamera(session)

        val linkedSession = session.copy(
            environmentSnapshotId = snapshot.id,
            revision = 1,
            updatedAt = snapshot.capturedAt,
        )

        assertEquals(
            EnvironmentSnapshotCaptureResult.Captured(snapshot, linkedSession),
            executions.capture(snapshot),
        )
        assertEquals(snapshot, executions.getEnvironmentSnapshot(snapshot.id))
        assertEquals(snapshot, executions.getEnvironmentSnapshotForSession(session.id))
        assertEquals(linkedSession, executions.get(session.id))
        assertEquals(
            linkedSession to snapshot,
            executions.report(session.id)?.let { it.session to it.environmentSnapshot },
        )

        val staleChange = session.copy(
            status = SessionStatus.STARTING,
            revision = 1,
            updatedAt = Instant.ofEpochMilli(4_000),
        )
        assertEquals(
            ExecutionApplyResult.RevisionConflict(0, 1),
            executions.apply(
                ExecutionChange(0, staleChange),
                event(session.id, "automation.start.stale_after_snapshot"),
            ),
        )
        assertEquals(linkedSession, executions.get(session.id))
    }

    @Test
    fun environmentSnapshotIsImmutableAndLimitedToOnePerSession() = runBlocking {
        val profile = profile()
        val schedule = schedule(profile.id)
        val session = session(schedule, profile.id)
        val original = environmentSnapshot(session.id)
        profiles.save(profile)
        schedules.save(schedule)
        executions.reservePixelCamera(session)
        executions.capture(original)
        val linkedSession = session.copy(
            environmentSnapshotId = original.id,
            revision = 1,
            updatedAt = original.capturedAt,
        )

        val replacement = original.copy(
            id = EnvironmentSnapshotId("snapshot-2"),
            batteryPercent = 25,
        )

        assertEquals(
            EnvironmentSnapshotCaptureResult.AlreadyExists(original, linkedSession),
            executions.capture(replacement),
        )
        assertEquals(original, executions.getEnvironmentSnapshotForSession(session.id))
        assertNull(executions.getEnvironmentSnapshot(replacement.id))
    }

    @Test
    fun activeRehearsalsAreBoundedAndOrderedByExpectedStop() = runBlocking {
        val pending = rehearsalSession(
            id = "pending",
            status = SessionStatus.PENDING,
            expectedStopAtEpochMs = 30_000,
        )
        val stopping = rehearsalSession(
            id = "stopping",
            status = SessionStatus.STOPPING,
            expectedStopAtEpochMs = 10_000,
            recordActionAt = Instant.ofEpochMilli(5_000),
        )
        val failedWithOwnership = rehearsalSession(
            id = "failed-owned",
            status = SessionStatus.FAILED,
            expectedStopAtEpochMs = 20_000,
            recordActionAt = Instant.ofEpochMilli(5_000),
        )
        val failedWithoutOwnership = rehearsalSession(
            id = "failed-unowned",
            status = SessionStatus.FAILED,
            expectedStopAtEpochMs = 5_000,
        )
        val failedAlreadyStopped = rehearsalSession(
            id = "failed-stopped",
            status = SessionStatus.FAILED,
            expectedStopAtEpochMs = 6_000,
            recordActionAt = Instant.ofEpochMilli(4_000),
            stoppedVerifiedAt = Instant.ofEpochMilli(5_000),
        )
        val completed = rehearsalSession(
            id = "completed",
            status = SessionStatus.COMPLETED,
            expectedStopAtEpochMs = 7_000,
        )
        listOf(
            pending,
            stopping,
            failedWithOwnership,
            failedWithoutOwnership,
            failedAlreadyStopped,
            completed,
        ).forEach { insertExecutionFixture(it) }

        assertEquals(
            listOf(stopping.id, failedWithOwnership.id),
            executions.findActiveRehearsals(limit = 2).map { it.id },
        )
        assertTrue(runCatching { executions.findActiveRehearsals(0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { executions.findActiveRehearsals(101) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun latestSuccessfulRehearsalRequiresBothVerificationProofs() = runBlocking {
        val profileId = ProfileId("profile-proof")
        val olderSuccess = rehearsalSession(
            id = "older-success",
            status = SessionStatus.COMPLETED,
            expectedStopAtEpochMs = 10_000,
            profileId = profileId,
            recordingVerifiedAt = Instant.ofEpochMilli(7_000),
            stoppedVerifiedAt = Instant.ofEpochMilli(8_000),
        )
        val latestSuccess = rehearsalSession(
            id = "latest-success",
            status = SessionStatus.COMPLETED,
            expectedStopAtEpochMs = 20_000,
            profileId = profileId,
            recordingVerifiedAt = Instant.ofEpochMilli(17_000),
            stoppedVerifiedAt = Instant.ofEpochMilli(18_000),
        )
        val missingStopProof = rehearsalSession(
            id = "missing-stop-proof",
            status = SessionStatus.COMPLETED,
            expectedStopAtEpochMs = 30_000,
            profileId = profileId,
            recordingVerifiedAt = Instant.ofEpochMilli(27_000),
        )
        val otherProfile = rehearsalSession(
            id = "other-profile",
            status = SessionStatus.COMPLETED,
            expectedStopAtEpochMs = 40_000,
            profileId = ProfileId("other-profile"),
            recordingVerifiedAt = Instant.ofEpochMilli(37_000),
            stoppedVerifiedAt = Instant.ofEpochMilli(38_000),
        )
        listOf(olderSuccess, latestSuccess, missingStopProof, otherProfile)
            .forEach { insertExecutionFixture(it) }

        assertEquals(
            latestSuccess,
            executions.latestSuccessfulRehearsal(profileId),
        )
        assertNull(executions.latestSuccessfulRehearsal(ProfileId("absent-profile")))
    }

    @Test
    fun pixelCameraReservationIsGlobalAndReleasesOnlyAfterOwnershipEnds() = runBlocking {
        val scheduled = rehearsalSession(
            id = "scheduled-owner",
            status = SessionStatus.PENDING,
            expectedStopAtEpochMs = 20_000,
            kind = SessionKind.SCHEDULED,
        )
        val rehearsal = rehearsalSession(
            id = "rehearsal-contender",
            status = SessionStatus.PENDING,
            expectedStopAtEpochMs = 30_000,
        )

        assertEquals(
            ExecutionReservationResult.Reserved(scheduled, newlyCreated = true),
            executions.reservePixelCamera(scheduled),
        )
        assertEquals(
            ExecutionReservationResult.Reserved(scheduled, newlyCreated = false),
            executions.reservePixelCamera(scheduled),
        )
        assertEquals(
            ExecutionReservationResult.CameraBusy(scheduled),
            executions.reservePixelCamera(rehearsal),
        )

        val failedWithOutstandingOwnership = scheduled.copy(
            status = SessionStatus.FAILED,
            recordActionAt = scheduled.updatedAt.plusMillis(100),
            revision = 1,
            updatedAt = scheduled.updatedAt.plusSeconds(1),
        )
        executions.apply(
            ExecutionChange(scheduled.revision, failedWithOutstandingOwnership),
            event(scheduled.id, "automation.owner.failed_outstanding"),
        )
        assertEquals(
            ExecutionReservationResult.CameraBusy(failedWithOutstandingOwnership),
            executions.reservePixelCamera(rehearsal),
        )

        val released = failedWithOutstandingOwnership.copy(
            stoppedVerifiedAt = failedWithOutstandingOwnership.updatedAt.plusMillis(100),
            revision = 2,
            updatedAt = failedWithOutstandingOwnership.updatedAt.plusSeconds(1),
        )
        executions.apply(
            ExecutionChange(failedWithOutstandingOwnership.revision, released),
            event(scheduled.id, "automation.owner.stop_verified"),
        )
        assertEquals(
            ExecutionReservationResult.Reserved(rehearsal, newlyCreated = true),
            executions.reservePixelCamera(rehearsal),
        )
    }

    @Test
    fun rebootRecoveryAtomicallyFailsAndReleasesEveryScheduledOwner() = runBlocking {
        val pending = rehearsalSession(
            id = "reboot-pending",
            status = SessionStatus.PENDING,
            expectedStopAtEpochMs = 20_000,
            kind = SessionKind.SCHEDULED,
        )
        val recording = rehearsalSession(
            id = "reboot-recording",
            status = SessionStatus.RECORDING,
            expectedStopAtEpochMs = 30_000,
            recordActionAt = Instant.ofEpochMilli(5_000),
            recordingVerifiedAt = Instant.ofEpochMilli(6_000),
            kind = SessionKind.SCHEDULED,
        )
        val stopping = rehearsalSession(
            id = "reboot-stopping",
            status = SessionStatus.STOPPING,
            expectedStopAtEpochMs = 40_000,
            recordActionAt = Instant.ofEpochMilli(5_000),
            kind = SessionKind.SCHEDULED,
        )
        val failedUncertain = rehearsalSession(
            id = "reboot-failed",
            status = SessionStatus.FAILED,
            expectedStopAtEpochMs = 50_000,
            recordActionAt = Instant.ofEpochMilli(5_000),
            kind = SessionKind.SCHEDULED,
        )
        val completed = rehearsalSession(
            id = "already-completed",
            status = SessionStatus.COMPLETED,
            expectedStopAtEpochMs = 60_000,
            kind = SessionKind.SCHEDULED,
        )
        listOf(pending, recording, stopping, failedUncertain, completed)
            .forEach { insertExecutionFixture(it) }
        val recoveredAt = Instant.ofEpochMilli(70_000)

        val recovered = executions.reconcileInterruptedScheduledSessions(recoveredAt)

        assertEquals(
            setOf(pending.id, recording.id, stopping.id, failedUncertain.id),
            recovered.map { it.id }.toSet(),
        )
        recovered.forEach { session ->
            assertEquals(SessionStatus.FAILED, session.status)
            assertEquals(AutomationStateName.FAILED, session.currentAutomationState)
            assertEquals(AutomationFailureCode.DEVICE_REBOOT_INTERRUPTED, session.failure?.code)
            assertEquals(recoveredAt, session.cameraOwnershipReleasedAt)
            assertNull(session.stoppedVerifiedAt)
            assertEquals(
                "automation.execution.reboot_interrupted",
                executions.observeEvents(session.id).first().single().name,
            )
        }
        assertEquals(completed, executions.get(completed.id))
        assertTrue(executions.reconcileInterruptedScheduledSessions(recoveredAt.plusSeconds(1)).isEmpty())

        val contender = rehearsalSession(
            id = "post-reboot-owner",
            status = SessionStatus.PENDING,
            expectedStopAtEpochMs = 80_000,
        )
        assertEquals(
            ExecutionReservationResult.Reserved(contender, newlyCreated = true),
            executions.reservePixelCamera(contender),
        )
    }

    @Test
    fun concurrentReservationsCreateExactlyOneGlobalOwner() = runBlocking {
        val contenders = (1..12).map { index ->
            rehearsalSession(
                id = "contender-$index",
                status = SessionStatus.PENDING,
                expectedStopAtEpochMs = 20_000L + index,
            )
        }

        val results = coroutineScope {
            contenders.map { contender ->
                async(Dispatchers.IO) { executions.reservePixelCamera(contender) }
            }.awaitAll()
        }
        val reservations = results.filterIsInstance<ExecutionReservationResult.Reserved>()
        val busy = results.filterIsInstance<ExecutionReservationResult.CameraBusy>()

        assertEquals(1, reservations.count { it.newlyCreated })
        assertEquals(11, busy.size)
        val owner = reservations.single().session
        assertTrue(busy.all { it.owner.id == owner.id })
        assertEquals(listOf(owner), executions.observeExecutions().first())
    }

    private fun profile(): PixelCameraProfile = PixelCameraProfile(
        id = ProfileId("profile-1"),
        environment = PixelCameraEnvironment(
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            androidSdk = 37,
            androidBuildFingerprint = "google/husky/test",
            cameraPackage = "com.google.android.GoogleCamera",
            cameraVersionCode = 700_000_000,
            localeTag = "en-US",
            displayWidthPx = 1_344,
            displayHeightPx = 2_992,
            densityDpi = 480,
        ),
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        targets = mapOf(
            AutomationAction.START_RECORDING to UiSelectorSet(
                selectors = listOf(
                    UiSelector(
                        packageName = "com.google.android.GoogleCamera",
                        resourceId = "verified.record.control",
                        expectedRegion = NormalizedBounds(0.3f, 0.7f, 0.7f, 1f),
                    ),
                ),
                minimumScore = 100,
            ),
            AutomationAction.SELECT_REAR_MAIN_LENS to UiSelectorSet(
                selectors = listOf(
                    UiSelector(
                        packageName = "com.google.android.GoogleCamera",
                        role = "android.widget.Button",
                        expectedRegion = NormalizedBounds(0f, 0.5f, 0.5f, 1f),
                    ),
                ),
                minimumScore = 20,
            ),
        ),
        speedTargets = mapOf(
            TimeLapseSpeed.X120 to UiSelectorSet(
                selectors = listOf(
                    UiSelector(
                        packageName = "com.google.android.GoogleCamera",
                        role = "android.widget.Button",
                    ),
                ),
                minimumScore = 20,
            ),
        ),
        stateSignals = mapOf(
            PixelCameraStateSignal.REAR_MAIN_LENS_ACTIVE to UiSelectorSet(
                selectors = listOf(
                    UiSelector(
                        packageName = "com.google.android.GoogleCamera",
                        role = "android.widget.Button",
                        expectedSelected = true,
                        expectedChecked = true,
                        requiresClickable = false,
                    ),
                ),
                minimumScore = 20,
            ),
            PixelCameraStateSignal.RECORDING_ACTIVE to UiSelectorSet(
                selectors = listOf(
                    UiSelector(
                        packageName = "com.google.android.GoogleCamera",
                        role = "android.widget.Button",
                        expectedSelected = true,
                        requiresClickable = false,
                    ),
                ),
                minimumScore = 20,
            ),
            PixelCameraStateSignal.NOT_RECORDING to UiSelectorSet(
                selectors = listOf(
                    UiSelector(
                        packageName = "com.google.android.GoogleCamera",
                        role = "android.widget.Button",
                        expectedSelected = false,
                        requiresClickable = false,
                    ),
                ),
                minimumScore = 20,
            ),
        ),
        fallbackGestures = mapOf(
            AutomationAction.START_RECORDING to GestureProfile(NormalizedPoint(0.5f, 0.85f)),
        ),
        compatibility = ProfileCompatibility.VERIFIED,
        verifiedAt = Instant.ofEpochMilli(1_000),
    )

    private fun schedule(profileId: ProfileId): RecordingSchedule = RecordingSchedule(
        id = ScheduleId("schedule-1"),
        name = "Sunrise",
        startAt = Instant.ofEpochMilli(10_000),
        stopAt = Instant.ofEpochMilli(20_000),
        zoneId = ZoneId.of("Asia/Tbilisi"),
        capture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X120,
            lens = LensSelection.REAR_MAIN,
        ),
        profileId = profileId,
        enabled = true,
        createdAt = Instant.ofEpochMilli(1_000),
        updatedAt = Instant.ofEpochMilli(2_000),
    )

    private fun session(
        schedule: RecordingSchedule,
        profileId: ProfileId,
    ): ExecutionSession = ExecutionSession(
        id = SessionId("session-1"),
        executionKey = "schedule-1:start:10000",
        kind = SessionKind.SCHEDULED,
        scheduleId = schedule.id,
        scheduleName = schedule.name,
        profileId = profileId,
        capture = schedule.capture,
        expectedStartAt = schedule.startAt,
        expectedStopAt = schedule.stopAt,
        status = SessionStatus.PENDING,
        revision = 0,
        createdAt = Instant.ofEpochMilli(3_000),
        updatedAt = Instant.ofEpochMilli(3_000),
    )

    private fun event(sessionId: SessionId, name: String): AutomationEvent = AutomationEvent(
        id = EventId("event-$name"),
        sessionId = sessionId,
        name = name,
        timestamp = Instant.ofEpochMilli(4_000),
        state = AutomationStateName.START_TRIGGERED,
        outcome = AutomationOutcome.SUCCEEDED,
        metadata = mapOf("source" to "alarm"),
    )

    private fun environmentSnapshot(sessionId: SessionId): EnvironmentSnapshot = EnvironmentSnapshot(
        id = EnvironmentSnapshotId("snapshot-1"),
        sessionId = sessionId,
        capturedAt = Instant.ofEpochMilli(3_500),
        lenswakeVersion = "1.0-debug",
        cameraEnvironment = profile().environment,
        accessibilityStatus = EnvironmentCapabilityStatus.AVAILABLE,
        privilegedBridgeStatus = EnvironmentCapabilityStatus.UNAVAILABLE,
        screenInteractive = false,
        keyguardLocked = true,
        batteryPercent = 75,
        charging = false,
        availableStorageBytes = 10_000_000_000,
    )

    private fun rehearsalSession(
        id: String,
        status: SessionStatus,
        expectedStopAtEpochMs: Long,
        profileId: ProfileId = ProfileId("profile-1"),
        recordActionAt: Instant? = null,
        recordingVerifiedAt: Instant? = null,
        stoppedVerifiedAt: Instant? = null,
        cameraOwnershipReleasedAt: Instant? = null,
        kind: SessionKind = SessionKind.REHEARSAL,
    ): ExecutionSession = ExecutionSession(
        id = SessionId(id),
        executionKey = "rehearsal/$id",
        kind = kind,
        scheduleId = if (kind == SessionKind.SCHEDULED) ScheduleId("schedule-$id") else null,
        scheduleName = "Rehearsal",
        profileId = profileId,
        capture = CaptureConfiguration.TimeLapse(TimeLapseSpeed.X120),
        expectedStartAt = Instant.ofEpochMilli(expectedStopAtEpochMs - 1_000),
        expectedStopAt = Instant.ofEpochMilli(expectedStopAtEpochMs),
        status = status,
        recordActionAt = recordActionAt,
        recordingVerifiedAt = recordingVerifiedAt,
        stoppedVerifiedAt = stoppedVerifiedAt,
        cameraOwnershipReleasedAt = cameraOwnershipReleasedAt,
        createdAt = Instant.ofEpochMilli(1_000),
        updatedAt = stoppedVerifiedAt ?: Instant.ofEpochMilli(2_000),
    )

    private suspend fun insertExecutionFixture(session: ExecutionSession) {
        check(database.executionDao().insertIgnoringConflict(session.toEntity()) != -1L)
    }
}
