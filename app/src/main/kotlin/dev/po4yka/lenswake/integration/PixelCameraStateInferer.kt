package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.automation.PixelCameraState
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.SelectorMatchResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraDialogKind
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.supportedCaptureConfigurations

internal class PixelCameraStateInferer(
    private val selectorMatcher: SelectorMatcher,
) {
    private val modeInferer = PixelCameraModeStateInferer()

    fun inspect(
        snapshot: AccessibilitySnapshotResult,
        profile: PixelCameraProfile,
    ): PortResult<PixelCameraState> = when (snapshot) {
        AccessibilitySnapshotResult.ServiceDisconnected -> unavailable(
            AutomationFailureCode.ACCESSIBILITY_DISABLED,
            "Lenswake Accessibility Service is not connected",
        )
        AccessibilitySnapshotResult.RefreshFailed -> unavailable(
            AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED,
            "The active Pixel Camera accessibility window could not be refreshed",
        )
        AccessibilitySnapshotResult.NoActiveWindow,
        AccessibilitySnapshotResult.PixelCameraNotForeground,
        -> PortResult.Observed(PixelCameraState.NotRunning)
        is AccessibilitySnapshotResult.Available -> if (snapshot.truncated) {
            unavailable(
                AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                "The bounded Pixel Camera accessibility snapshot was truncated",
            )
        } else {
            infer(profile, snapshot.nodes)
        }
    }

    fun infer(
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): PortResult<PixelCameraState> {
        inferDialog(profile, nodes)?.let { return it }
        val missingSignals = requiredSignals(profile) - profile.stateSignals.keys
        return if (missingSignals.isEmpty()) {
            inferEvidence(collectEvidence(profile, nodes))
        } else {
            unavailableMissingSignals(missingSignals)
        }
    }

    private fun inferDialog(
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): PortResult<PixelCameraState>? {
        val matches = linkedSetOf<PixelCameraDialogKind>()
        profile.dialogProfiles.forEach { (kind, dialogProfile) ->
            when (selectorMatcher.match(dialogProfile.presence, profile, nodes)) {
                is SelectorMatchResult.Match -> matches += kind
                is SelectorMatchResult.Ambiguous -> return PortResult.Unavailable(
                    AutomationFailure(
                        AutomationFailureCode.UI_TARGET_AMBIGUOUS,
                        "The Pixel Camera dialog presence signal was ambiguous",
                        mapOf("dialog" to kind.name),
                    ),
                )
                is SelectorMatchResult.BelowThreshold,
                SelectorMatchResult.NoEligibleNodes,
                SelectorMatchResult.TargetNotConfigured,
                -> Unit
            }
        }
        val typed = matches - PixelCameraDialogKind.UNKNOWN
        return when {
            typed.size > 1 -> PortResult.Unavailable(
                AutomationFailure(
                    AutomationFailureCode.UNEXPECTED_CAMERA_DIALOG,
                    "Multiple Pixel Camera dialog types matched the same snapshot",
                    mapOf("dialogs" to typed.sortedBy { it.name }.joinToString(",") { it.name }),
                ),
            )
            typed.size == 1 -> PortResult.Observed(PixelCameraState.Dialog(typed.single()))
            PixelCameraDialogKind.UNKNOWN in matches ->
                PortResult.Observed(PixelCameraState.Dialog(PixelCameraDialogKind.UNKNOWN))
            else -> null
        }
    }

    private fun requiredSignals(profile: PixelCameraProfile): Set<PixelCameraStateSignal> = buildSet {
        add(PixelCameraStateSignal.PHOTO_MODE_ACTIVE)
        add(PixelCameraStateSignal.RECORDING_ACTIVE)
        add(PixelCameraStateSignal.NOT_RECORDING)
        profile.supportedCaptureConfigurations().forEach { addCaptureSignals(it) }
    }

    private fun MutableSet<PixelCameraStateSignal>.addCaptureSignals(
        capture: CaptureConfiguration,
    ) {
        add(lensSignals.entries.single { it.value == capture.lens }.key)
        when (capture) {
            is CaptureConfiguration.Video -> {
                add(PixelCameraStateSignal.VIDEO_MODE_ACTIVE)
                add(PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE)
                add(PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE)
            }
            is CaptureConfiguration.TimeLapse -> {
                add(PixelCameraStateSignal.VIDEO_MODE_ACTIVE)
                add(PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE)
                add(PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN)
                add(speedSignals.entries.single { it.value == capture.speed }.key)
            }
            is CaptureConfiguration.NightSightTimeLapse ->
                add(PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE)
        }
    }

    private fun collectEvidence(
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): StateEvidence {
        val active = linkedSetOf<PixelCameraStateSignal>()
        val ambiguous = linkedSetOf<PixelCameraStateSignal>()
        profile.stateSignals.forEach { (signal, selectorSet) ->
            when (selectorMatcher.match(selectorSet, profile, nodes)) {
                is SelectorMatchResult.Match -> active += signal
                is SelectorMatchResult.Ambiguous -> ambiguous += signal
                is SelectorMatchResult.BelowThreshold,
                SelectorMatchResult.NoEligibleNodes,
                SelectorMatchResult.TargetNotConfigured,
                -> Unit
            }
        }
        return StateEvidence(active, ambiguous)
    }

    private fun inferEvidence(evidence: StateEvidence): PortResult<PixelCameraState> =
        when (val recording = evidence.recording()) {
            is RecordingEvidence.Invalid -> recording.failure
            is RecordingEvidence.Known -> inferKnownRecording(evidence, recording.active)
        }

    private fun inferKnownRecording(
        evidence: StateEvidence,
        recording: Boolean,
    ): PortResult<PixelCameraState> = when {
        evidence.ambiguous.isEmpty() -> modeInferer.infer(evidence.active, recording)
        recording && evidence.ambiguous.all(stopOptionalSignals::contains) ->
            PortResult.Observed(PixelCameraState.RecordingUnknownMode)
        else -> unavailableAmbiguousState(evidence.ambiguous)
    }

}

private class PixelCameraModeStateInferer {
    fun infer(
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
    ): PortResult<PixelCameraState> {
        val activeLenses = activeLensValues(active)
        return when {
            activeLenses.size > 1 -> unavailableConflictingState("lens", activeLenses)
            PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN in active ->
                inferPicker(active, recording)
            else -> inferMode(active, recording)
        }
    }

    private fun inferPicker(
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
    ): PortResult<PixelCameraState> {
        val activeSpeeds = activeSpeedValues(active)
        return when {
            recording -> PortResult.Observed(PixelCameraState.RecordingUnknownMode)
            activeSpeeds.size > 1 -> unavailableConflictingState("timeLapseSpeed", activeSpeeds)
            else -> PortResult.Observed(
                PixelCameraState.TimeLapseSpeedPicker(
                    speed = activeSpeeds.singleOrNull(),
                    recording = false,
                    lens = inferLens(active),
                ),
            )
        }
    }

    private fun inferMode(
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
    ): PortResult<PixelCameraState> {
        val modeSignals = active.intersect(cameraModeSignals)
        return when {
            modeSignals.size == 1 -> inferModeState(modeSignals.single(), active, recording)
            recording && modeSignals.isEmpty() ->
                PortResult.Observed(PixelCameraState.RecordingUnknownMode)
            else -> unavailableConflictingState("mode", modeSignals)
        }
    }

    private fun inferModeState(
        mode: PixelCameraStateSignal,
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
    ): PortResult<PixelCameraState> = when (mode) {
        PixelCameraStateSignal.PHOTO_MODE_ACTIVE -> if (recording) {
            PortResult.Observed(PixelCameraState.RecordingUnknownMode)
        } else {
            PortResult.Observed(PixelCameraState.Photo)
        }
        PixelCameraStateSignal.VIDEO_MODE_ACTIVE -> inferVideo(active, recording)
        PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE -> inferTimeLapse(active, recording)
        PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE -> inferNightSight(active, recording)
        else -> error("Only mode signals are considered")
    }

    private fun inferVideo(
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
    ): PortResult<PixelCameraState> = inferLensBoundRecording(active, recording) { lens ->
        PixelCameraState.Video(
            recording = recording,
            lens = lens,
            resolution4k = PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE in active,
            frameRate60 = PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE in active,
        )
    }

    private fun inferNightSight(
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
    ): PortResult<PixelCameraState> = inferLensBoundRecording(active, recording) { lens ->
        PixelCameraState.NightSightTimeLapse(recording, lens)
    }

    private fun inferLensBoundRecording(
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
        state: (LensSelection?) -> PixelCameraState,
    ): PortResult<PixelCameraState> {
        val lens = inferLens(active)
        return if (recording && lens == null) {
            PortResult.Observed(PixelCameraState.RecordingUnknownMode)
        } else {
            PortResult.Observed(state(lens))
        }
    }

    private fun inferTimeLapse(
        active: Set<PixelCameraStateSignal>,
        recording: Boolean,
    ): PortResult<PixelCameraState> {
        val activeSpeeds = activeSpeedValues(active)
        val speed = activeSpeeds.singleOrNull()
        val lens = inferLens(active)
        return when {
            activeSpeeds.size > 1 -> unavailableConflictingState("timeLapseSpeed", activeSpeeds)
            recording && (speed == null || lens == null) ->
                PortResult.Observed(PixelCameraState.RecordingUnknownMode)
            else -> PortResult.Observed(PixelCameraState.TimeLapse(speed, recording, lens))
        }
    }

    private companion object {
        val cameraModeSignals = setOf(
            PixelCameraStateSignal.PHOTO_MODE_ACTIVE,
            PixelCameraStateSignal.VIDEO_MODE_ACTIVE,
            PixelCameraStateSignal.TIME_LAPSE_MODE_ACTIVE,
            PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE,
        )
    }
}

private data class StateEvidence(
    val active: Set<PixelCameraStateSignal>,
    val ambiguous: Set<PixelCameraStateSignal>,
) {
    fun recording(): RecordingEvidence {
        val recordingStateSignals = setOf(
            PixelCameraStateSignal.RECORDING_ACTIVE,
            PixelCameraStateSignal.NOT_RECORDING,
        )
        val ambiguousRecording = ambiguous.intersect(recordingStateSignals)
        val activeRecording = active.intersect(recordingStateSignals)
        return when {
            ambiguousRecording.isNotEmpty() -> RecordingEvidence.Invalid(
                unavailableAmbiguousState(ambiguousRecording),
            )
            activeRecording.size != 1 -> RecordingEvidence.Invalid(
                unavailableConflictingState("recording", activeRecording),
            )
            else -> RecordingEvidence.Known(
                PixelCameraStateSignal.RECORDING_ACTIVE in activeRecording,
            )
        }
    }
}

private sealed interface RecordingEvidence {
    data class Known(val active: Boolean) : RecordingEvidence

    data class Invalid(val failure: PortResult.Unavailable) : RecordingEvidence
}

private fun activeSpeedValues(active: Set<PixelCameraStateSignal>): Set<TimeLapseSpeed> =
    speedSignals.filterKeys(active::contains).values.toSet()

private fun activeLensValues(active: Set<PixelCameraStateSignal>): Set<LensSelection> =
    lensSignals.filterKeys(active::contains).values.toSet()

private fun inferLens(active: Set<PixelCameraStateSignal>): LensSelection? =
    activeLensValues(active).singleOrNull()

private fun unavailableMissingSignals(
    missingSignals: Set<PixelCameraStateSignal>,
): PortResult.Unavailable = PortResult.Unavailable(
    AutomationFailure(
        code = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
        message = "The profile lacks required observable Pixel Camera state signals",
        context = mapOf(
            "missingSignals" to missingSignals.sortedBy { it.name }.joinToString(",") { it.name },
        ),
    ),
)

private fun unavailableConflictingState(
    dimension: String,
    values: Set<*>,
): PortResult.Unavailable = PortResult.Unavailable(
    AutomationFailure(
        code = AutomationFailureCode.CAMERA_STATE_UNKNOWN,
        message = "Pixel Camera $dimension state is missing or conflicting",
        context = mapOf("matches" to values.joinToString(",")),
    ),
)

private fun unavailableAmbiguousState(
    signals: Set<PixelCameraStateSignal>,
): PortResult.Unavailable = PortResult.Unavailable(
    AutomationFailure(
        code = AutomationFailureCode.UI_TARGET_AMBIGUOUS,
        message = "The Pixel Camera state signal was ambiguous",
        context = mapOf("signals" to signals.sortedBy { it.name }.joinToString(",") { it.name }),
    ),
)

private fun unavailable(
    code: AutomationFailureCode,
    message: String,
): PortResult.Unavailable = PortResult.Unavailable(accessibilityFailure(code, message))
