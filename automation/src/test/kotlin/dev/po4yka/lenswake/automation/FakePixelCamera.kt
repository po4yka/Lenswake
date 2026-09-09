package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.PixelCameraDialogKind
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.util.concurrent.CancellationException
import kotlinx.coroutines.awaitCancellation

internal class FakePixelCamera(
        private var state: PixelCameraState,
        private val preparationFailure: AutomationFailure? = null,
        private val confirmStart: Boolean = true,
        private val confirmStop: Boolean = true,
        private val confirmLens: Boolean = true,
        private val confirmSpeedPicker: Boolean = true,
        private val speedPickerOpensOnAttempt: Int? = null,
        private val speedPickerDispatch: ActionDispatch? = null,
        private val hideLensInSpeedPicker: Boolean = false,
        private val keepSpeedPickerOpenAfterSelection: Boolean = false,
        private val confirmSpeedPickerClose: Boolean = true,
        private val speedPickerCloseDispatch: ActionDispatch? = null,
        private val suspendLaunch: Boolean = false,
        private val cancelLaunch: Boolean = false,
        private val suspendStart: Boolean = false,
        private val recordingStartsOnVerificationInspection: Int? = null,
        private val startException: Exception? = null,
        private val startDispatch: ActionDispatch? = null,
        private val startDispatchMetadata: Map<String, String> = emptyMap(),
        private val onStartRecording: (suspend () -> Unit)? = null,
        private val suspendStop: Boolean = false,
        private val stopException: Exception? = null,
        private val stopDispatch: ActionDispatch? = null,
        private val onStopRecording: (suspend () -> Unit)? = null,
        private val stateAfterStop: PixelCameraState? = null,
        private val stopCompletesOnVerificationInspection: Int? = null,
        private val stateAfterDialogRecovery: PixelCameraState? = null,
        private val dialogRecoveryDispatch: ActionDispatch? = null,
        private val inspectionFailureAfterDialogRecovery: AutomationFailure? = null,
    ) : PixelCameraPort {
        val calls = mutableListOf<String>()
        val trace = mutableListOf<String>()
        var verificationInspections = 0
        var stopVerificationInspections = 0
        val receivedProfileUses = mutableListOf<ProfileUse>()
        val stopModes = mutableListOf<CaptureMode>()
        val receivedProfiles: List<PixelCameraProfile>
            get() = receivedProfileUses.map(ProfileUse::profile)
        var lensWasRearMainWhenRecordStarted: Boolean = false
        private var lensBeforeSpeedPicker: LensSelection? = null

        override suspend fun prepareCapture(
            capture: CaptureConfiguration,
            profileUse: ProfileUse,
        ): PortResult<CaptureConfiguration> {
            receivedProfileUses += profileUse
            trace += "prepareCapture"
            preparationFailure?.let { return PortResult.Unavailable(it) }
            if (capture is CaptureConfiguration.Video) {
                calls += "prepareVideo"
                state = (state as PixelCameraState.Video).copy(resolution4k = true, frameRate60 = true)
            }
            if (capture is CaptureConfiguration.NightSightTimeLapse) {
                calls += "prepareNightSight"
                state = PixelCameraState.NightSightTimeLapse(recording = false, lens = capture.lens)
            }
            return PortResult.Observed(capture)
        }

        override suspend fun inspect(profileUse: ProfileUse): PortResult<PixelCameraState> {
            receivedProfileUses += profileUse
            trace += "inspect"
            if (calls.lastOrNull()?.startsWith("recoverDialog:") == true) {
                inspectionFailureAfterDialogRecovery?.let { return PortResult.Unavailable(it) }
            }
            if (
                calls.lastOrNull() == "startRecording" &&
                state is PixelCameraState.TimeLapse &&
                !(state as PixelCameraState.TimeLapse).recording
            ) {
                verificationInspections += 1
                if (recordingStartsOnVerificationInspection != null &&
                    verificationInspections >= recordingStartsOnVerificationInspection
                ) {
                    state = (state as PixelCameraState.TimeLapse).copy(recording = true)
                }
            }
            if (
                calls.lastOrNull() == "stopRecording" &&
                state is PixelCameraState.TimeLapse &&
                (state as PixelCameraState.TimeLapse).recording
            ) {
                stopVerificationInspections += 1
                if (
                    stopCompletesOnVerificationInspection != null &&
                    stopVerificationInspections >= stopCompletesOnVerificationInspection
                ) {
                    state = stateAfterStop ?: (state as PixelCameraState.TimeLapse).copy(recording = false)
                }
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
            state = PixelCameraState.Video(recording = false, lens = null)
            return dispatched()
        }

        override suspend fun selectVideoResolution4k(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "selectVideoResolution4k"
            state = (state as PixelCameraState.Video).copy(resolution4k = true)
            return dispatched()
        }

        override suspend fun selectVideoFrameRate60(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "selectVideoFrameRate60"
            state = (state as PixelCameraState.Video).copy(frameRate60 = true)
            return dispatched()
        }

        override suspend fun selectTimeLapse(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "selectTimeLapse"
            state = PixelCameraState.TimeLapse(speed = null, recording = false, lens = null)
            return dispatched()
        }

        override suspend fun selectNightSightTimeLapse(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "selectNightSightTimeLapse"
            // The fake models Pixel Camera closing the control row once the option is selected.
            state = PixelCameraState.NightSightTimeLapse(recording = false, lens = null)
            return dispatched()
        }

        override suspend fun openNightSightTimeLapseControl(profileUse: ProfileUse): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "openNightSightTimeLapseControl"
            val current = state as PixelCameraState.TimeLapse
            state = PixelCameraState.NightSightTimeLapseControl(
                nightSightOn = false,
                recording = current.recording,
                lens = current.lens,
            )
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
                lensBeforeSpeedPicker = current.lens
                state = PixelCameraState.TimeLapseSpeedPicker(
                    speed = current.speed,
                    recording = current.recording,
                    lens = if (hideLensInSpeedPicker) null else current.lens,
                )
            }
            return speedPickerDispatch ?: dispatched()
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

        override suspend fun closeTimeLapseSpeedControl(
            expectedSpeed: TimeLapseSpeed?,
            profileUse: ProfileUse,
        ): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "closeTimeLapseSpeedControl"
            speedPickerCloseDispatch?.let { return it }
            if (confirmSpeedPickerClose) {
                val current = state as PixelCameraState.TimeLapseSpeedPicker
                check(expectedSpeed == null || current.speed == expectedSpeed)
                state = PixelCameraState.TimeLapse(
                    speed = current.speed,
                    recording = current.recording,
                    lens = current.lens ?: lensBeforeSpeedPicker,
                )
            }
            return dispatched()
        }

        override suspend fun selectLens(
            lens: LensSelection,
            profileUse: ProfileUse,
        ): ActionDispatch {
            receivedProfileUses += profileUse
            calls += if (lens == LensSelection.REAR_MAIN) "selectRearMainLens" else "selectLens:$lens"
            if (confirmLens) {
                state = when (val current = state) {
                    is PixelCameraState.Video -> current.copy(lens = lens)
                    is PixelCameraState.TimeLapse -> current.copy(lens = lens)
                    is PixelCameraState.NightSightTimeLapse -> current.copy(lens = lens)
                    else -> error("Lens selection requires a configurable capture state")
                }
            }
            return dispatched()
        }

        override suspend fun startRecording(
            mode: CaptureMode,
            profileUse: ProfileUse,
        ): ActionDispatch {
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
                    is PixelCameraState.Video -> state = current.copy(recording = true)
                    is PixelCameraState.NightSightTimeLapse -> state = current.copy(recording = true)
                    else -> error("Record dispatch requires a configurable capture state")
                }
            }
            return dispatched(startDispatchMetadata)
        }

        override suspend fun stopRecording(
            mode: CaptureMode,
            profileUse: ProfileUse,
        ): ActionDispatch {
            receivedProfileUses += profileUse
            stopModes += mode
            calls += "stopRecording"
            trace += "stop"
            onStopRecording?.invoke()
            stopException?.let { throw it }
            stopDispatch?.let { return it }
            if (confirmStop && stopCompletesOnVerificationInspection == null) {
                state = stateAfterStop ?: when (val current = state) {
                    is PixelCameraState.Video -> current.copy(recording = false)
                    is PixelCameraState.TimeLapse -> current.copy(recording = false)
                    is PixelCameraState.NightSightTimeLapse -> current.copy(recording = false)
                    else -> error("Stop dispatch requires a recording capture state")
                }
            }
            if (suspendStop) awaitCancellation()
            return dispatched()
        }

        override suspend fun recoverDialog(
            dialog: PixelCameraDialogKind,
            profileUse: ProfileUse,
        ): ActionDispatch {
            receivedProfileUses += profileUse
            calls += "recoverDialog:$dialog"
            dialogRecoveryDispatch?.let { return it }
            state = stateAfterDialogRecovery ?: state
            return dispatched()
        }

        private fun dispatched(metadata: Map<String, String> = emptyMap()) =
            ActionDispatch.Dispatched(InteractionMethod.ACCESSIBILITY_ACTION, metadata)
    }
