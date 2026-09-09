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
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal class PreparationRejected(val failure: AutomationFailure) : RuntimeException(failure.message)

internal class PixelCameraPreparationObserver(
    private val matcher: SelectorMatcher,
    private val gateway: PixelCameraAccessibilityGateway,
) {
    private val inferer = PixelCameraStateInferer(matcher)
    fun idleCaptureMatches(
        capture: CaptureConfiguration,
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): Boolean {
        val state = (inferer.infer(profile, nodes) as? PortResult.Observed)?.value ?: return false
        return matchesIdleCapture(state, capture)
    }

    private fun matchesIdleCapture(state: PixelCameraState, capture: CaptureConfiguration): Boolean = when (state) {
        is PixelCameraState.Video -> capture is CaptureConfiguration.Video &&
            !state.recording && state.lens == capture.lens
        is PixelCameraState.TimeLapse -> !state.recording && state.lens == capture.lens && when (capture) {
            is CaptureConfiguration.TimeLapse -> state.speed == capture.speed
            is CaptureConfiguration.NightSightTimeLapse -> true
            else -> false
        }
        is PixelCameraState.NightSightTimeLapse -> capture is CaptureConfiguration.NightSightTimeLapse &&
            !state.recording && state.lens == capture.lens
        else -> false
    }

    fun match(signal: PixelCameraStateSignal, profile: PixelCameraProfile, nodes: List<UiNodeSnapshot>) =
        profile.stateSignals[signal]?.let { matcher.match(it, profile, nodes) }
            ?: SelectorMatchResult.TargetNotConfigured

    fun matches(
        signal: PixelCameraStateSignal, profile: PixelCameraProfile, nodes: List<UiNodeSnapshot>,
    ): Boolean =
        when (match(signal, profile, nodes)) {
            is SelectorMatchResult.Match -> true
            is SelectorMatchResult.Ambiguous -> reject("Ambiguous settings evidence: $signal")
            else -> false
        }

    suspend fun awaitNodes(stage: String, predicate: (List<UiNodeSnapshot>) -> Boolean) {
        val confirmed = withTimeoutOrNull(POSTCONDITION_TIMEOUT_MS) {
            while (!predicate(readNodes())) delay(OBSERVATION_INTERVAL_MS)
            true
        } == true
        if (!confirmed) reject("Timed out verifying $stage")
    }

    suspend fun readNodes(): List<UiNodeSnapshot> = when (val result = gateway.snapshot()) {
        is AccessibilitySnapshotResult.Available -> {
            if (result.truncated) reject("Pixel Camera snapshot was truncated")
            result.nodes
        }
        else -> reject("Pixel Camera became unavailable during capture preparation")
    }

    private fun reject(message: String): Nothing = throw PreparationRejected(
        AutomationFailure(AutomationFailureCode.CAMERA_STATE_UNKNOWN, message,
            mapOf("operation" to "PREPARE_CAPTURE")),
    )

    private companion object {
        const val POSTCONDITION_TIMEOUT_MS = 3_000L
        const val OBSERVATION_INTERVAL_MS = 100L
    }
}
