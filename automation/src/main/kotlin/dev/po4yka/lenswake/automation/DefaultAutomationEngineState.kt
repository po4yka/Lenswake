package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.AutomationOperation
import dev.po4yka.lenswake.core.AutomationOutcome
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.AutomationStateName
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.EventId
import dev.po4yka.lenswake.core.ExecutionApplyResult
import dev.po4yka.lenswake.core.ExecutionChange
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.supports
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal fun EngineEnvironment.eventName(
    state: AutomationStateName,
    operation: AutomationOperation?,
    outcome: AutomationOutcome,
): String = when (state) {
    AutomationStateName.RECORDING -> "automation.record.start_verified"
    AutomationStateName.RECOVERING_CAMERA_DIALOG ->
        if (outcome == AutomationOutcome.DISPATCHED) {
            "automation.camera_dialog.recovery_dispatched"
        } else {
            defaultEventName(state, operation, outcome)
        }
    AutomationStateName.VERIFYING_CAMERA_DIALOG_RECOVERY ->
        if (outcome == AutomationOutcome.SUCCEEDED) {
            "automation.camera_dialog.recovery_verified"
        } else {
            defaultEventName(state, operation, outcome)
        }
    AutomationStateName.VERIFYING_MEDIA_SAVED ->
        if (operation == AutomationOperation.VERIFY_MEDIA_SAVED && outcome == AutomationOutcome.SUCCEEDED) {
            "automation.media.save_verified"
        } else {
            defaultEventName(state, operation, outcome)
        }
    AutomationStateName.COMPLETED -> if (operation == AutomationOperation.VERIFY_STOPPED) {
        "automation.record.stop_verified_media_unavailable_legacy"
    } else {
        "automation.record.stop_and_save_verified"
    }
    AutomationStateName.FAILED -> failureEventName(operation, outcome)
    else -> defaultEventName(state, operation, outcome)
}

private fun failureEventName(
    operation: AutomationOperation?,
    outcome: AutomationOutcome,
): String = when {
    outcome != AutomationOutcome.SUCCEEDED -> "automation.failed"
    operation == AutomationOperation.VERIFY_MEDIA_SAVED ->
        "automation.record.stop_and_save_verified_after_failure"
    operation == AutomationOperation.VERIFY_STOPPED -> "automation.record.stop_verified_after_failure"
    else -> "automation.failed"
}

private fun defaultEventName(
    state: AutomationStateName,
    operation: AutomationOperation?,
    outcome: AutomationOutcome,
): String = if (outcome == AutomationOutcome.DISPATCHED && operation != null) {
    "automation.${operation.name.lowercase()}.dispatched"
} else {
    "automation.state.${state.name.lowercase()}"
}

internal fun PixelCameraState.isConfirmedRecording(capture: CaptureConfiguration): Boolean = when (capture) {
        is CaptureConfiguration.Video ->
            this is PixelCameraState.Video && recording && lens == capture.lens
        is CaptureConfiguration.TimeLapse ->
            this is PixelCameraState.TimeLapse &&
                recording &&
                speed == capture.speed &&
                lens == capture.lens
        is CaptureConfiguration.NightSightTimeLapse ->
            this is PixelCameraState.NightSightTimeLapse && recording && lens == capture.lens
    }

internal fun PixelCameraState.isConfirmedStopped(): Boolean = when (this) {
        PixelCameraState.Photo -> true
        is PixelCameraState.Video -> !recording
        is PixelCameraState.TimeLapse -> !recording
        is PixelCameraState.TimeLapseSpeedPicker -> !recording
        is PixelCameraState.NightSightTimeLapse -> !recording
        PixelCameraState.NotRunning,
        PixelCameraState.Unknown,
        PixelCameraState.RecordingUnknownMode,
        is PixelCameraState.Dialog,
        -> false
    }

internal fun PixelCameraState.isRecording(): Boolean = when (this) {
        is PixelCameraState.Video -> recording
        is PixelCameraState.TimeLapse -> recording
        is PixelCameraState.TimeLapseSpeedPicker -> recording
        is PixelCameraState.NightSightTimeLapse -> recording
        PixelCameraState.RecordingUnknownMode -> true
        PixelCameraState.Photo,
        PixelCameraState.NotRunning,
        PixelCameraState.Unknown,
        is PixelCameraState.Dialog,
        -> false
    }

internal fun PixelCameraState.observedLens(): LensSelection? = when (this) {
        is PixelCameraState.Video -> lens
        is PixelCameraState.TimeLapse -> lens
        is PixelCameraState.TimeLapseSpeedPicker -> lens
        is PixelCameraState.NightSightTimeLapse -> lens
        else -> null
    }

internal fun PixelCameraState.observedMode(): CaptureMode? = when (this) {
        is PixelCameraState.Video -> CaptureMode.VIDEO
        is PixelCameraState.TimeLapse,
        is PixelCameraState.TimeLapseSpeedPicker,
        -> CaptureMode.TIME_LAPSE
        is PixelCameraState.NightSightTimeLapse -> CaptureMode.NIGHT_SIGHT_TIME_LAPSE
        else -> null
    }

internal fun ExecutionSession.hasUncertainRecordDispatch(): Boolean =
        recordActionAt != null && recordingVerifiedAt == null && stoppedVerifiedAt == null

internal class EngineAbort(
        val result: AutomationRunResult,
    ) : RuntimeException(null, null, false, false)

internal sealed interface TimedCall<out T> {
        data class Completed<T>(
            val value: T,
        ) : TimedCall<T>

        data object TimedOut : TimedCall<Nothing>
}
