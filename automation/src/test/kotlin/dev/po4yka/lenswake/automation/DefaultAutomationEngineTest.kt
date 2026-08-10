package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionReservationResult
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.Zoom
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class DefaultAutomationEngineTest {
    @Test
    fun `start converges from sleeping Photo mode and persists confirmed recording`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(state = PixelCameraState.Photo)
        val engine = engine(repository, device, camera)

        val result = engine.start(session.id)

        val succeeded = assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertEquals(SessionStatus.RECORDING, succeeded.session.status)
        assertEquals(AutomationStateName.RECORDING, succeeded.session.currentAutomationState)
        assertNotNull(succeeded.session.recordActionAt)
        assertNotNull(succeeded.session.recordingVerifiedAt)
        assertEquals(
            listOf(
                "wake",
                "launch",
                "selectVideo",
                "selectTimeLapse",
                "selectRearMainLens",
                "openTimeLapseSpeedControl",
                "selectSpeed:X120",
                "startRecording",
            ),
            device.calls + camera.calls,
        )
        assertEquals(repository.appliedChanges.size, repository.events.size)
        assertEquals(repository.appliedChanges.size.toLong(), succeeded.session.revision)
        assertEquals(AutomationStateName.START_TRIGGERED, repository.events.first().state)
        assertEquals(AutomationStateName.RECORDING, repository.events.last().state)
        assertEquals(setOf(ProfileId("profile")), camera.receivedProfiles.map { it.id }.toSet())
        assertEquals(setOf(ProfileUse.Kind.UNATTENDED), camera.receivedProfileUses.map { it.kind }.toSet())
    }

    @Test
    fun `rehearsal session supplies rehearsal profile use for the complete start flow`() = runTest {
        val session = session(status = SessionStatus.PENDING, kind = SessionKind.REHEARSAL)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(state = readyToRecordState())
        val engine = engine(
            repository = repository,
            device = device,
            camera = camera,
            profile = profile().copy(compatibility = ProfileCompatibility.NEEDS_REHEARSAL),
        )

        val result = engine.start(session.id)

        assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertTrue(camera.receivedProfileUses.isNotEmpty())
        assertEquals(
            setOf(ProfileUse.Kind.REHEARSAL),
            camera.receivedProfileUses.map { it.kind }.toSet(),
        )
    }

    @Test
    fun `record dispatch without confirmation requires inspect-only reconciliation`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
            confirmStart = false,
        )
        val engine = engine(repository, device, camera, attempts = 2)

        val result = engine.start(session.id)

        val reconciliation = assertInstanceOf(
            AutomationRunResult.StartReconciliationRequired::class.java,
            result,
        )
        assertEquals(SessionStatus.FAILED, reconciliation.session.status)
        assertEquals(AutomationFailureCode.RECORDING_NOT_CONFIRMED, reconciliation.failure.code)
        assertNotNull(reconciliation.session.recordActionAt)
        assertNull(reconciliation.session.recordingVerifiedAt)
        assertEquals(1, camera.calls.count { it == "startRecording" })
        assertEquals(2, camera.verificationInspections)
        assertEquals(AutomationStateName.FAILED, repository.events.last().state)
    }

    @Test
    fun `record checkpoint is persisted before invoking Pixel Camera`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        var checkpointAtPortCall: ExecutionSession? = null
        val camera = FakePixelCamera(
            state = readyToRecordState(),
            onStartRecording = { checkpointAtPortCall = repository.get(session.id) },
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera)

        val result = engine.start(session.id)

        assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertNotNull(checkpointAtPortCall?.recordActionAt)
        assertNull(checkpointAtPortCall?.recordingVerifiedAt)
        assertEquals(AutomationStateName.STARTING_RECORDING, checkpointAtPortCall?.currentAutomationState)
    }

    @Test
    fun `definitive record rejection clears write-ahead checkpoint`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val rejection = AutomationFailure(
            AutomationFailureCode.RECORD_ACTION_FAILED,
            "Pixel Camera definitively rejected Record",
        )
        val camera = FakePixelCamera(
            state = readyToRecordState(),
            startDispatch = ActionDispatch.Rejected(rejection),
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera, attempts = 1)

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(rejection, failed.failure)
        assertNull(failed.session.recordActionAt)
        assertEquals(1, camera.calls.count { it == "startRecording" })
    }

    @Test
    fun `record timeout leaves checkpoint for later stop reconciliation`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = readyToRecordState(),
            suspendStart = true,
        )
        val engine = engine(
            repository,
            FakeDeviceControl(interactive = true),
            camera,
            attempts = 1,
            timeout = 100.milliseconds,
        )

        val result = engine.start(session.id)

        val reconciliation = assertInstanceOf(
            AutomationRunResult.StartReconciliationRequired::class.java,
            result,
        )
        assertEquals(AutomationFailureCode.AUTOMATION_TIMEOUT, reconciliation.failure.code)
        assertNotNull(reconciliation.session.recordActionAt)
        assertNull(reconciliation.session.recordingVerifiedAt)

        val recoveryCamera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val recovered = engine(
            repository,
            FakeDeviceControl(interactive = true),
            recoveryCamera,
        ).start(session.id)

        assertInstanceOf(AutomationRunResult.Succeeded::class.java, recovered)
        assertEquals(0, recoveryCamera.calls.count { it == "startRecording" })
    }

    @Test
    fun `record exception leaves checkpoint for reconciliation`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = readyToRecordState(),
            startException = IllegalStateException("binder failed after transaction"),
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera)

        val result = engine.start(session.id)

        val reconciliation = assertInstanceOf(
            AutomationRunResult.StartReconciliationRequired::class.java,
            result,
        )
        assertEquals(AutomationFailureCode.RECORD_ACTION_FAILED, reconciliation.failure.code)
        assertNotNull(reconciliation.session.recordActionAt)
        assertNull(reconciliation.session.recordingVerifiedAt)
    }

    @Test
    fun `record cancellation propagates with checkpoint preserved`() {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = readyToRecordState(),
            startException = CancellationException("cancelled after possible dispatch"),
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera)

        assertThrows(CancellationException::class.java) {
            runTest { engine.start(session.id) }
        }
        var persisted: ExecutionSession? = null
        runTest { persisted = repository.get(session.id) }
        assertNotNull(persisted?.recordActionAt)
    }

    @Test
    fun `resumed start reconciles uncertain dispatch by observation without dispatching again`() = runTest {
        val session = session(status = SessionStatus.STARTING).copy(
            currentAutomationState = AutomationStateName.STARTING_RECORDING,
            recordActionAt = NOW.minusSeconds(30),
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera)

        val result = engine.start(session.id)

        val succeeded = assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertNotNull(succeeded.session.recordingVerifiedAt)
        assertEquals(0, camera.calls.count { it == "launch" })
        assertEquals(0, camera.calls.count { it == "startRecording" })
    }

    @Test
    fun `resumed uncertain start never redispatches when recording is not observed`() = runTest {
        val session = session(status = SessionStatus.STARTING).copy(
            currentAutomationState = AutomationStateName.STARTING_RECORDING,
            recordActionAt = NOW.minusSeconds(30),
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(state = readyToRecordState())
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera)

        val result = engine.start(session.id)

        val reconciliation = assertInstanceOf(
            AutomationRunResult.StartReconciliationRequired::class.java,
            result,
        )
        assertEquals(AutomationFailureCode.RECORDING_NOT_CONFIRMED, reconciliation.failure.code)
        assertNotNull(reconciliation.session.recordActionAt)
        assertEquals(0, camera.calls.count { it == "launch" })
        assertEquals(0, camera.calls.count { it == "startRecording" })
    }

    @Test
    fun `start selects rear main lens and verifies it before recording`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X120,
                recording = false,
                lens = LensSelection.FRONT,
            ),
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera)

        val result = engine.start(session.id)

        assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertEquals(listOf("launch", "selectRearMainLens", "startRecording"), camera.calls)
        assertTrue(camera.lensWasRearMainWhenRecordStarted)
    }

    @Test
    fun `start verifies speed picker before selecting requested speed`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X30,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
            confirmSpeedPicker = false,
        )

        val result = engine(
            repository,
            FakeDeviceControl(interactive = true),
            camera,
            attempts = 2,
        ).start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.TIME_LAPSE_SPEED_NOT_VERIFIED, failed.failure.code)
        assertEquals(2, camera.calls.count { it == "openTimeLapseSpeedControl" })
        assertEquals(0, camera.calls.count { it == "selectSpeed:X120" })
        assertEquals(0, camera.calls.count { it == "startRecording" })
        assertTrue(repository.events.any {
            it.state == AutomationStateName.OPENING_TIME_LAPSE_SPEED_CONTROL &&
                it.operation == AutomationOperation.OPEN_TIME_LAPSE_SPEED_CONTROL &&
                it.outcome == AutomationOutcome.DISPATCHED
        })
        assertTrue(repository.events.any {
            it.state == AutomationStateName.VERIFYING_TIME_LAPSE_SPEED_CONTROL &&
                it.operation == AutomationOperation.OPEN_TIME_LAPSE_SPEED_CONTROL
        })
    }

    @Test
    fun `start redispatches speed picker opener when first cold gesture does not converge`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X30,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
            speedPickerOpensOnAttempt = 2,
        )

        val result = engine(
            repository,
            FakeDeviceControl(interactive = true),
            camera,
            attempts = 2,
        ).start(session.id)

        assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertEquals(2, camera.calls.count { it == "openTimeLapseSpeedControl" })
        assertEquals(1, camera.calls.count { it == "selectSpeed:X120" })
        assertEquals(1, camera.calls.count { it == "startRecording" })
        assertTrue(repository.events.any {
            it.state == AutomationStateName.RETRYING &&
                it.operation == AutomationOperation.OPEN_TIME_LAPSE_SPEED_CONTROL &&
                it.attempt == 2
        })
    }

    @Test
    fun `start records once when selected speed remains in the open picker`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X30,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
            keepSpeedPickerOpenAfterSelection = true,
        )

        val result = engine(repository, FakeDeviceControl(interactive = true), camera).start(session.id)

        assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertEquals(
            listOf(
                "launch",
                "openTimeLapseSpeedControl",
                "selectSpeed:X120",
                "startRecording",
            ),
            camera.calls,
        )
        assertTrue(camera.lensWasRearMainWhenRecordStarted)
    }

    @Test
    fun `start fails when rear main lens postcondition is not observed`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                speed = TimeLapseSpeed.X120,
                recording = false,
                lens = LensSelection.FRONT,
            ),
            confirmLens = false,
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera, attempts = 2)

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.LENS_NOT_VERIFIED, failed.failure.code)
        assertEquals(0, camera.calls.count { it == "startRecording" })
    }

    @Test
    fun `start does not claim an existing recording without a dispatched record action`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, device, camera)

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.RECORDING_NOT_CONFIRMED, failed.failure.code)
        assertNull(failed.session.recordActionAt)
        assertNull(failed.session.recordingVerifiedAt)
        assertEquals(0, camera.calls.count { it == "startRecording" })
    }

    @Test
    fun `persisted recording status without ownership evidence is rejected`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, device, camera)

        val result = engine.start(session.id)

        val rejected = assertInstanceOf(AutomationRunResult.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.SESSION_STATE_CONFLICT, rejected.failure.code)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
    }

    @Test
    fun `stop wakes device and completes only after stopped state is observed`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        val succeeded = assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertEquals(SessionStatus.COMPLETED, succeeded.session.status)
        assertEquals(AutomationStateName.COMPLETED, succeeded.session.currentAutomationState)
        assertNotNull(succeeded.session.stopActionAt)
        assertNotNull(succeeded.session.stoppedVerifiedAt)
        assertEquals(listOf("wake", "stopRecording"), device.calls + camera.calls)
        assertEquals("inspect", camera.trace.first())
        assertEquals(0, camera.calls.count { it == "launch" })
    }

    @Test
    fun `stop accepts a verified owned recording when Pixel Camera hides mode controls`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.RecordingUnknownMode,
            stateAfterStop = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera)

        val result = engine.stop(session.id)

        val succeeded = assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertEquals(SessionStatus.COMPLETED, succeeded.session.status)
        assertNotNull(succeeded.session.stopActionAt)
        assertNotNull(succeeded.session.stoppedVerifiedAt)
        assertEquals(1, camera.calls.count { it == "stopRecording" })
    }

    @Test
    fun `stop recovers hidden-mode recording after write-ahead dispatch without promoting success`() = runTest {
        val session = session(status = SessionStatus.STARTING).copy(
            currentAutomationState = AutomationStateName.VERIFYING_RECORDING,
            recordActionAt = NOW.minusSeconds(60),
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.RecordingUnknownMode,
            stateAfterStop = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = false,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, FakeDeviceControl(interactive = true), camera)

        val result = engine.stop(session.id)

        val recovered = assertInstanceOf(AutomationRunResult.StopVerifiedAfterFailure::class.java, result)
        assertEquals(SessionStatus.FAILED, recovered.session.status)
        assertEquals(AutomationFailureCode.RECORDING_NOT_CONFIRMED, recovered.session.failure?.code)
        assertNull(recovered.session.recordingVerifiedAt)
        assertNotNull(recovered.session.stopActionAt)
        assertNotNull(recovered.session.stoppedVerifiedAt)
        assertEquals(1, camera.calls.count { it == "stopRecording" })
    }

    @Test
    fun `stop dispatch without stopped confirmation exhausts verification policy and fails`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
            confirmStop = false,
        )
        val engine = engine(repository, device, camera, attempts = 2)

        val result = engine.stop(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.STOP_NOT_CONFIRMED, failed.failure.code)
        assertEquals(SessionStatus.FAILED, failed.session.status)
        assertNotNull(failed.session.stopActionAt)
        assertNull(failed.session.stoppedVerifiedAt)
        assertEquals(1, camera.calls.count { it == "stopRecording" })
        assertEquals(2, camera.stopVerificationInspections)
    }

    @Test
    fun `stop timeout preserves write-ahead checkpoint and reconciles before any redispatch`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        var checkpointAtPortCall: ExecutionSession? = null
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
            suspendStop = true,
            onStopRecording = { checkpointAtPortCall = repository.get(session.id) },
        )
        val engine = engine(
            repository = repository,
            device = FakeDeviceControl(interactive = true),
            camera = camera,
            attempts = 3,
            timeout = 100.milliseconds,
        )

        val timedOut = engine.stop(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, timedOut)
        assertEquals(AutomationFailureCode.AUTOMATION_TIMEOUT, failed.failure.code)
        assertNotNull(checkpointAtPortCall?.stopActionAt)
        assertNotNull(failed.session.stopActionAt)
        assertNull(failed.session.stoppedVerifiedAt)
        assertEquals(1, camera.calls.count { it == "stopRecording" })

        val reconciled = engine.stop(session.id)

        assertInstanceOf(AutomationRunResult.StopVerifiedAfterFailure::class.java, reconciled)
        assertEquals(1, camera.calls.count { it == "stopRecording" })
        assertEquals(
            listOf("inspect", "stop", "inspect", "inspect"),
            camera.trace.filter { it == "inspect" || it == "stop" },
        )
    }

    @Test
    fun `stop exception preserves checkpoint without redispatching`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
            stopException = IllegalStateException("binder failed after transaction"),
        )

        val result = engine(repository, FakeDeviceControl(true), camera, attempts = 3).stop(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.STOP_ACTION_FAILED, failed.failure.code)
        assertNotNull(failed.session.stopActionAt)
        assertNull(failed.session.stoppedVerifiedAt)
        assertEquals(1, camera.calls.count { it == "stopRecording" })
    }

    @Test
    fun `stop cancellation propagates with write-ahead checkpoint preserved`() {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
            stopException = CancellationException("cancelled after possible dispatch"),
        )

        assertThrows(CancellationException::class.java) {
            runTest { engine(repository, FakeDeviceControl(true), camera).stop(session.id) }
        }
        var persisted: ExecutionSession? = null
        runTest { persisted = repository.get(session.id) }
        assertNotNull(persisted?.stopActionAt)
        assertEquals(1, camera.calls.count { it == "stopRecording" })
    }

    @Test
    fun `definitive stop rejection clears checkpoint before bounded retry`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val rejection = AutomationFailure(
            AutomationFailureCode.STOP_ACTION_FAILED,
            "Pixel Camera definitively rejected Stop",
        )
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
            stopDispatch = ActionDispatch.Rejected(rejection),
        )

        val result = engine(repository, FakeDeviceControl(true), camera, attempts = 2).stop(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(rejection, failed.failure)
        assertNull(failed.session.stopActionAt)
        assertEquals(2, camera.calls.count { it == "stopRecording" })
    }

    @Test
    fun `cancelled session cleans up only an outstanding owned recording`() = runTest {
        val session = session(status = SessionStatus.CANCELLED).copy(
            currentAutomationState = AutomationStateName.CANCELLED,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )

        val result = engine(repository, FakeDeviceControl(true), camera).stop(session.id)

        val succeeded = assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertEquals(SessionStatus.COMPLETED, succeeded.session.status)
        assertNotNull(succeeded.session.stoppedVerifiedAt)
        assertEquals(1, camera.calls.count { it == "stopRecording" })
    }

    @Test
    fun `cancelled session without record ownership remains terminal`() = runTest {
        val session = session(status = SessionStatus.CANCELLED).copy(
            currentAutomationState = AutomationStateName.CANCELLED,
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)

        val result = engine(repository, FakeDeviceControl(false), camera).stop(session.id)

        assertInstanceOf(AutomationRunResult.AlreadyTerminal::class.java, result)
        assertEquals(emptyList<String>(), camera.calls)
    }

    @Test
    fun `cancelled session with verified stop remains terminal`() = runTest {
        val session = session(status = SessionStatus.CANCELLED).copy(
            currentAutomationState = AutomationStateName.CANCELLED,
            recordActionAt = NOW.minusSeconds(60),
            stoppedVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)

        val result = engine(repository, FakeDeviceControl(false), camera).stop(session.id)

        assertInstanceOf(AutomationRunResult.AlreadyTerminal::class.java, result)
        assertEquals(emptyList<String>(), camera.calls)
    }

    @Test
    fun `stop rejects a recording session without Lenswake ownership`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(TimeLapseSpeed.X120, recording = true),
        )
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        val rejected = assertInstanceOf(AutomationRunResult.Rejected::class.java, result)
        assertEquals(AutomationFailureCode.SESSION_STATE_CONFLICT, rejected.failure.code)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
        assertNull(rejected.session.stoppedVerifiedAt)
    }

    @Test
    fun `stop does not dispatch against a recording with different session semantics`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X30,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.STOP_NOT_CONFIRMED, failed.failure.code)
        assertEquals(0, camera.calls.count { it == "stopRecording" })
        assertNull(failed.session.stoppedVerifiedAt)
    }

    @Test
    fun `dispatched but unverified recording is stopped and remains failed`() = runTest {
        val session = session(status = SessionStatus.STARTING).copy(
            currentAutomationState = AutomationStateName.VERIFYING_RECORDING,
            recordActionAt = NOW.minusSeconds(30),
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        val recovered = assertInstanceOf(AutomationRunResult.StopVerifiedAfterFailure::class.java, result)
        assertEquals(SessionStatus.FAILED, recovered.session.status)
        assertEquals(AutomationFailureCode.RECORDING_NOT_CONFIRMED, recovered.session.failure?.code)
        assertNotNull(recovered.session.stoppedVerifiedAt)
        assertEquals(listOf("stopRecording"), camera.calls)
    }

    @Test
    fun `stop inspects before launching and launches only when Pixel Camera is not running`() = runTest {
        val session = session(status = SessionStatus.RECORDING).copy(
            currentAutomationState = AutomationStateName.RECORDING,
            recordActionAt = NOW.minusSeconds(60),
            recordingVerifiedAt = NOW,
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(state = PixelCameraState.NotRunning)
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        val succeeded = assertInstanceOf(AutomationRunResult.Succeeded::class.java, result)
        assertEquals(SessionStatus.COMPLETED, succeeded.session.status)
        assertEquals(listOf("inspect", "launch"), camera.trace.take(2))
        assertEquals(1, camera.calls.count { it == "launch" })
        assertEquals(0, camera.calls.count { it == "stopRecording" })
    }

    @Test
    fun `stop safely ends late recording while preserving original failed session`() = runTest {
        val originalFailure = AutomationFailure(
            AutomationFailureCode.RECORDING_NOT_CONFIRMED,
            "Recording was not confirmed before the start deadline",
        )
        val session = session(status = SessionStatus.FAILED).copy(
            currentAutomationState = AutomationStateName.FAILED,
            recordActionAt = NOW.minusSeconds(30),
            failure = originalFailure,
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(
            state = PixelCameraState.TimeLapse(
                TimeLapseSpeed.X120,
                recording = true,
                lens = LensSelection.REAR_MAIN,
            ),
        )
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        val recovered = assertInstanceOf(AutomationRunResult.StopVerifiedAfterFailure::class.java, result)
        assertEquals(SessionStatus.FAILED, recovered.session.status)
        assertEquals(AutomationStateName.FAILED, recovered.session.currentAutomationState)
        assertEquals(originalFailure, recovered.session.failure)
        assertNotNull(recovered.session.stoppedVerifiedAt)
        assertEquals(listOf("stopRecording"), device.calls + camera.calls)
        assertEquals(AutomationOperation.VERIFY_STOPPED, repository.events.last().operation)
        assertEquals(AutomationOutcome.SUCCEEDED, repository.events.last().outcome)
        assertEquals("automation.record.stop_verified_after_failure", repository.events.last().name)
    }

    @Test
    fun `failed session without dispatched record remains terminal`() = runTest {
        val session = session(status = SessionStatus.FAILED).copy(
            currentAutomationState = AutomationStateName.FAILED,
            failure = AutomationFailure(AutomationFailureCode.WAKE_FAILED, "Wake failed"),
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        assertInstanceOf(AutomationRunResult.AlreadyTerminal::class.java, result)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
        assertEquals(0, repository.events.size)
    }

    @Test
    fun `failed session with an already verified stop remains terminal`() = runTest {
        val session = session(status = SessionStatus.FAILED).copy(
            currentAutomationState = AutomationStateName.FAILED,
            recordActionAt = NOW.minusSeconds(60),
            stoppedVerifiedAt = NOW,
            failure = AutomationFailure(AutomationFailureCode.STOP_NOT_CONFIRMED, "Original failure"),
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        assertInstanceOf(AutomationRunResult.AlreadyTerminal::class.java, result)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
        assertEquals(0, repository.events.size)
    }

    @Test
    fun `missing persisted profile fails before device or camera mutation`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(repository, device, camera, profile = null)

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_NOT_FOUND, failed.failure.code)
        assertEquals(SessionStatus.FAILED, failed.session.status)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
        assertEquals(emptyList<PixelCameraProfile>(), camera.receivedProfiles)
    }

    @Test
    fun `incompatible persisted profile fails before device or camera mutation`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(
            repository,
            device,
            camera,
            profile = profile().copy(compatibility = ProfileCompatibility.INCOMPATIBLE),
        )

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, failed.failure.code)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
        assertEquals(emptyList<PixelCameraProfile>(), camera.receivedProfiles)
    }

    @Test
    fun `scheduled execution rejects a probably compatible profile`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(
            repository,
            device,
            camera,
            profile = profile().copy(compatibility = ProfileCompatibility.PROBABLY_COMPATIBLE),
        )

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL, failed.failure.code)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
    }

    @Test
    fun `scheduled execution rejects a profile requiring rehearsal before device or camera mutation`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(
            repository,
            device,
            camera,
            profile = profile().copy(compatibility = ProfileCompatibility.NEEDS_REHEARSAL),
        )

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL, failed.failure.code)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
        assertEquals(emptyList<ProfileUse>(), camera.receivedProfileUses)
    }

    @Test
    fun `unsupported lens fails before device or camera action`() = runTest {
        val capture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X120,
            lens = LensSelection.FRONT,
        )
        val session = session(status = SessionStatus.PENDING, capture = capture)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(repository, device, camera)

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.UNSUPPORTED_CAPTURE_CONFIGURATION, failed.failure.code)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
    }

    @Test
    fun `unsupported zoom fails before device or camera action`() = runTest {
        val capture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X120,
            zoom = Zoom.of(2f),
        )
        val session = session(status = SessionStatus.PENDING, capture = capture)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(repository, device, camera)

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.UNSUPPORTED_CAPTURE_CONFIGURATION, failed.failure.code)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
    }

    @Test
    fun `operation timeout becomes typed failure`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(PixelCameraState.NotRunning, suspendLaunch = true)
        val engine = engine(repository, device, camera, attempts = 1, timeout = 100.milliseconds)

        val result = engine.start(session.id)

        val failed = assertInstanceOf(AutomationRunResult.Failed::class.java, result)
        assertEquals(AutomationFailureCode.AUTOMATION_TIMEOUT, failed.failure.code)
        assertEquals("LAUNCH_CAMERA", failed.failure.context["operation"])
    }

    @Test
    fun `port cancellation is propagated`() {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = true)
        val camera = FakePixelCamera(PixelCameraState.NotRunning, cancelLaunch = true)
        val engine = engine(repository, device, camera)

        assertThrows(CancellationException::class.java) {
            runTest { engine.start(session.id) }
        }
    }

    @Test
    fun `terminal status is idempotent and performs no external work`() = runTest {
        val session = session(status = SessionStatus.COMPLETED).copy(
            currentAutomationState = AutomationStateName.COMPLETED,
        )
        val repository = FakeExecutionRepository(session)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(repository, device, camera)

        val result = engine.stop(session.id)

        val alreadySatisfied = assertInstanceOf(AutomationRunResult.AlreadyTerminal::class.java, result)
        assertEquals(session, alreadySatisfied.session)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
        assertEquals(0, repository.appliedChanges.size)
    }

    @Test
    fun `revision conflict stops before invoking device or camera`() = runTest {
        val session = session(status = SessionStatus.PENDING)
        val repository = FakeExecutionRepository(session, conflictOnNextApply = true)
        val device = FakeDeviceControl(interactive = false)
        val camera = FakePixelCamera(PixelCameraState.NotRunning)
        val engine = engine(repository, device, camera)

        val result = engine.start(session.id)

        assertInstanceOf(AutomationRunResult.RevisionConflict::class.java, result)
        assertEquals(emptyList<String>(), device.calls + camera.calls)
    }

    private fun engine(
        repository: FakeExecutionRepository,
        device: FakeDeviceControl,
        camera: FakePixelCamera,
        attempts: Int = 3,
        profile: PixelCameraProfile? = profile(),
        timeout: Duration = 5_000.milliseconds,
    ) = DefaultAutomationEngine(
        executionRepository = repository,
        profileRepository = FakeProfileRepository(profile),
        deviceControl = device,
        pixelCamera = camera,
        clock = { NOW },
        config = AutomationConfig(
            retryPolicies = AutomationOperation.entries.associateWith {
                RetryPolicy(
                    maxAttempts = attempts,
                    initialDelay = Duration.ZERO,
                    maxDelay = Duration.ZERO,
                    multiplier = 1.0,
                )
            },
            operationTimeouts = AutomationOperation.entries.associateWith { timeout },
            maxConvergenceSteps = 10,
        ),
        sleeper = AutomationSleeper { },
    )

    private fun session(
        status: SessionStatus,
        capture: CaptureConfiguration = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X120,
            lens = LensSelection.REAR_MAIN,
        ),
        kind: SessionKind = SessionKind.SCHEDULED,
    ) = ExecutionSession(
        id = SessionId("session"),
        executionKey = "${kind.name.lowercase()}:session",
        kind = kind,
        scheduleId = if (kind == SessionKind.SCHEDULED) ScheduleId("schedule") else null,
        scheduleName = if (kind == SessionKind.SCHEDULED) "Sunrise" else null,
        profileId = ProfileId("profile"),
        capture = capture,
        expectedStartAt = Instant.parse("2026-08-10T01:00:00Z"),
        expectedStopAt = Instant.parse("2026-08-10T03:00:00Z"),
        status = status,
        createdAt = Instant.parse("2026-08-09T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-09T00:00:00Z"),
    )

    private fun profile() = PixelCameraProfile(
        id = ProfileId("profile"),
        environment = PixelCameraEnvironment(
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            androidSdk = 37,
            androidBuildFingerprint = "verified",
            cameraPackage = "com.google.android.GoogleCamera",
            cameraVersionCode = 1,
            localeTag = "en-US",
            displayWidthPx = 1344,
            displayHeightPx = 2992,
            densityDpi = 480,
        ),
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        compatibility = ProfileCompatibility.VERIFIED,
        verifiedAt = NOW,
    )

    private fun readyToRecordState() = PixelCameraState.TimeLapse(
        speed = TimeLapseSpeed.X120,
        recording = false,
        lens = LensSelection.REAR_MAIN,
    )

    private class FakeDeviceControl(
        interactive: Boolean,
    ) : DeviceControlPort {
        private var state = DeviceState(interactive = interactive)
        val calls = mutableListOf<String>()

        override suspend fun inspect(): PortResult<DeviceState> = PortResult.Observed(state)

        override suspend fun wake(): ActionDispatch {
            calls += "wake"
            state = DeviceState(interactive = true)
            return ActionDispatch.Dispatched(InteractionMethod.PRIVILEGED_INPUT)
        }
    }

    private class FakePixelCamera(
        private var state: PixelCameraState,
        private val confirmStart: Boolean = true,
        private val confirmStop: Boolean = true,
        private val confirmLens: Boolean = true,
        private val confirmSpeedPicker: Boolean = true,
        private val speedPickerOpensOnAttempt: Int? = null,
        private val keepSpeedPickerOpenAfterSelection: Boolean = false,
        private val suspendLaunch: Boolean = false,
        private val cancelLaunch: Boolean = false,
        private val suspendStart: Boolean = false,
        private val startException: Exception? = null,
        private val startDispatch: ActionDispatch? = null,
        private val onStartRecording: (suspend () -> Unit)? = null,
        private val suspendStop: Boolean = false,
        private val stopException: Exception? = null,
        private val stopDispatch: ActionDispatch? = null,
        private val onStopRecording: (suspend () -> Unit)? = null,
        private val stateAfterStop: PixelCameraState? = null,
    ) : PixelCameraPort {
        val calls = mutableListOf<String>()
        val trace = mutableListOf<String>()
        var verificationInspections = 0
        var stopVerificationInspections = 0
        val receivedProfileUses = mutableListOf<ProfileUse>()
        val receivedProfiles: List<PixelCameraProfile>
            get() = receivedProfileUses.map(ProfileUse::profile)
        var lensWasRearMainWhenRecordStarted: Boolean = false

        override suspend fun inspect(profileUse: ProfileUse): PortResult<PixelCameraState> {
            receivedProfileUses += profileUse
            trace += "inspect"
            if (calls.lastOrNull() == "startRecording" && state is PixelCameraState.TimeLapse && !(state as PixelCameraState.TimeLapse).recording) {
                verificationInspections += 1
            }
            if (calls.lastOrNull() == "stopRecording" && state is PixelCameraState.TimeLapse && (state as PixelCameraState.TimeLapse).recording) {
                stopVerificationInspections += 1
            }
            return PortResult.Observed(state)
        }

        override suspend fun launchSecureCamera(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "launch"
            trace += "launch"
            if (cancelLaunch) throw CancellationException("cancelled by caller")
            if (suspendLaunch) awaitCancellation()
            if (state == PixelCameraState.NotRunning) state = PixelCameraState.Photo
            return dispatched()
        }

        override suspend fun selectVideo(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "selectVideo"
            state = PixelCameraState.Video(recording = false)
            return dispatched()
        }

        override suspend fun selectTimeLapse(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "selectTimeLapse"
            state = PixelCameraState.TimeLapse(speed = null, recording = false, lens = null)
            return dispatched()
        }

        override suspend fun openTimeLapseSpeedControl(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "openTimeLapseSpeedControl"
            if (confirmSpeedPicker &&
                (speedPickerOpensOnAttempt == null ||
                    calls.count { it == "openTimeLapseSpeedControl" } >= speedPickerOpensOnAttempt)
            ) {
                val current = state as PixelCameraState.TimeLapse
                state = PixelCameraState.TimeLapseSpeedPicker(
                    speed = current.speed,
                    recording = current.recording,
                    lens = current.lens,
                )
            }
            return dispatched()
        }

        override suspend fun selectTimeLapseSpeed(
            speed: TimeLapseSpeed,
            profileUse: ProfileUse,
        ): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "selectSpeed:$speed"
            val current = state as PixelCameraState.TimeLapseSpeedPicker
            state = if (keepSpeedPickerOpenAfterSelection) {
                current.copy(speed = speed)
            } else {
                PixelCameraState.TimeLapse(
                    speed = speed,
                    recording = current.recording,
                    lens = current.lens,
                )
            }
            return dispatched()
        }

        override suspend fun selectRearMainLens(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "selectRearMainLens"
            if (confirmLens) {
                val current = state as PixelCameraState.TimeLapse
                state = current.copy(lens = LensSelection.REAR_MAIN)
            }
            return dispatched()
        }

        override suspend fun startRecording(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "startRecording"
            onStartRecording?.invoke()
            startException?.let { throw it }
            if (suspendStart) awaitCancellation()
            startDispatch?.let { return it }
            if (confirmStart) {
                when (val current = state) {
                    is PixelCameraState.TimeLapse -> {
                        lensWasRearMainWhenRecordStarted = current.lens == LensSelection.REAR_MAIN
                        state = current.copy(recording = true)
                    }
                    is PixelCameraState.TimeLapseSpeedPicker -> {
                        lensWasRearMainWhenRecordStarted = current.lens == LensSelection.REAR_MAIN
                        state = current.copy(recording = true)
                    }
                    else -> error("Record dispatch requires Time Lapse state")
                }
            }
            return dispatched()
        }

        override suspend fun stopRecording(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "stopRecording"
            trace += "stop"
            onStopRecording?.invoke()
            stopException?.let { throw it }
            stopDispatch?.let { return it }
            if (confirmStop) {
                state = stateAfterStop ?: (state as PixelCameraState.TimeLapse).copy(recording = false)
            }
            if (suspendStop) awaitCancellation()
            return dispatched()
        }

        private fun dispatched() = ActionDispatch.Dispatched(InteractionMethod.ACCESSIBILITY_ACTION)
    }

    private class FakeProfileRepository(
        profile: PixelCameraProfile?,
    ) : AutomationProfileRepository {
        private val profiles = MutableStateFlow(listOfNotNull(profile))

        override fun observeProfiles(): Flow<List<PixelCameraProfile>> = profiles

        override suspend fun get(id: ProfileId): PixelCameraProfile? =
            profiles.value.firstOrNull { it.id == id }

        override suspend fun save(profile: PixelCameraProfile) {
            profiles.value = profiles.value.filterNot { it.id == profile.id } + profile
        }

        override suspend fun delete(id: ProfileId) {
            profiles.value = profiles.value.filterNot { it.id == id }
        }
    }

    private class FakeExecutionRepository(
        session: ExecutionSession,
        private var conflictOnNextApply: Boolean = false,
    ) : ExecutionRepository {
        private val execution = MutableStateFlow<ExecutionSession?>(session)
        private val allExecutions = MutableStateFlow(listOf(session))
        private val allEvents = MutableStateFlow<List<AutomationEvent>>(emptyList())
        val appliedChanges = mutableListOf<ExecutionChange>()
        val events = mutableListOf<AutomationEvent>()

        override fun observeExecutions(): Flow<List<ExecutionSession>> = allExecutions
        override fun observeExecution(id: SessionId): Flow<ExecutionSession?> = execution
        override fun observeEvents(sessionId: SessionId): Flow<List<AutomationEvent>> = allEvents
        override suspend fun get(id: SessionId): ExecutionSession? = execution.value?.takeIf { it.id == id }
        override suspend fun findActiveForSchedule(scheduleId: ScheduleId): ExecutionSession? =
            execution.value?.takeIf { it.scheduleId == scheduleId }

        override suspend fun reservePixelCamera(session: ExecutionSession): ExecutionReservationResult {
            execution.value = session
            allExecutions.value = listOf(session)
            return ExecutionReservationResult.Reserved(session, newlyCreated = true)
        }

        override suspend fun apply(
            change: ExecutionChange,
            event: AutomationEvent,
        ): ExecutionApplyResult {
            val current = execution.value
            if (conflictOnNextApply) {
                conflictOnNextApply = false
                return ExecutionApplyResult.RevisionConflict(change.expectedRevision, current?.revision)
            }
            if (current?.revision != change.expectedRevision) {
                return ExecutionApplyResult.RevisionConflict(change.expectedRevision, current?.revision)
            }
            execution.value = change.updatedSession
            allExecutions.value = listOf(change.updatedSession)
            appliedChanges += change
            events += event
            allEvents.value = events.toList()
            return ExecutionApplyResult.Applied(change.updatedSession)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T12:00:00Z")
    }
}
