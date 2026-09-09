package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.automation.SelectorMatchResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraStateSignal

/**
 * Bounded visible-settings transaction. No cached settings are fed into inspection: the caller
 * receives proof only after selection, panel dismissal, and a fresh idle mode/lens recheck.
 * Record is a separate operation and cannot be dispatched by this component.
 */
internal class PixelCameraCapturePreparer(
    private val matcher: SelectorMatcher,
    private val gateway: PixelCameraAccessibilityGateway,
    private val validator: PixelCameraProfileValidator,
) {
    private val dispatcher = PixelCameraActionDispatcher(matcher, gateway)
    private val observer = PixelCameraPreparationObserver(matcher, gateway)

    suspend fun prepare(capture: CaptureConfiguration, use: ProfileUse): PortResult<CaptureConfiguration> {
        validator.validate(use)?.let { return PortResult.Unavailable(it) }
        return try {
            requireIdleCapture(capture, use.profile, observer.readNodes())
            when (capture) {
                is CaptureConfiguration.Video -> prepareVideo(use)
                is CaptureConfiguration.NightSightTimeLapse -> prepareNightSight(use, enabled = true)
                is CaptureConfiguration.TimeLapse -> {
                    if (AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE_OFF in use.profile.targets) {
                        prepareNightSight(use, enabled = false)
                    }
                }
            }
            observer.awaitNodes("closed capture UI") { nodes ->
                observer.idleCaptureMatches(capture, use.profile, nodes)
            }
            PortResult.Observed(capture)
        } catch (rejected: PreparationRejected) {
            PortResult.Unavailable(rejected.failure)
        }
    }

    private suspend fun prepareVideo(use: ProfileUse) {
        val panel = PixelCameraStateSignal.VIDEO_SETTINGS_OPEN
        openPanel(use, panel, AutomationAction.OPEN_VIDEO_SETTINGS)
        selectAndVerify(use, PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE,
            AutomationAction.SELECT_VIDEO_RESOLUTION_4K)
        selectAndVerify(use, PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE,
            AutomationAction.SELECT_VIDEO_FRAME_RATE_60)
        val nodes = observer.readNodes()
        if (!observer.matches(PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE, use.profile, nodes) ||
            !observer.matches(PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE, use.profile, nodes)
        ) reject("4K and 60 FPS were not simultaneously selected")
        closePanel(use, panel) { fresh ->
            observer.matches(PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE, use.profile, fresh) &&
                observer.matches(PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE, use.profile, fresh)
        }
    }

    private suspend fun prepareNightSight(use: ProfileUse, enabled: Boolean) {
        val panel = PixelCameraStateSignal.TIME_LAPSE_SETTINGS_OPEN
        openPanel(use, panel, AutomationAction.OPEN_NIGHT_SIGHT_TIME_LAPSE_CONTROL)
        if (observer.matches(PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_UNAVAILABLE, use.profile,
                observer.readNodes())) {
            closePanel(use, panel)
            if (enabled) throw PreparationRejected(AutomationFailure(
                AutomationFailureCode.UNSUPPORTED_CAPTURE_CONFIGURATION,
                "Pixel Camera reports Night Sight Time Lapse unavailable for the selected lens",
            ))
            return
        }
        if (!nightSelectionMatches(enabled, use.profile, observer.readNodes())) {
            dispatch(use, if (enabled) AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE
                else AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE_OFF)
        }
        observer.awaitNodes("Night Sight selection") { nodes ->
            observer.matches(panel, use.profile, nodes) && nightSelectionMatches(enabled, use.profile, nodes)
        }
        closePanel(use, panel) { fresh -> nightSelectionMatches(enabled, use.profile, fresh) }
    }

    private fun nightSelectionMatches(
        enabled: Boolean, profile: PixelCameraProfile, nodes: List<UiNodeSnapshot>,
    ): Boolean {
        if (enabled) return observer.matches(PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE, profile, nodes)
        val target = profile.targets[AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE_OFF]
            ?: reject("The profile has no explicit Night Sight off control")
        val selectedTarget = target.copy(selectors = target.selectors.map { it.copy(expectedSelected = true) })
        return matcher.match(selectedTarget, profile, nodes) is SelectorMatchResult.Match
    }

    private suspend fun openPanel(use: ProfileUse, signal: PixelCameraStateSignal, action: AutomationAction) {
        if (!observer.matches(signal, use.profile, observer.readNodes())) dispatch(use, action)
        observer.awaitNodes("$signal open") { observer.matches(signal, use.profile, it) }
    }

    private suspend fun selectAndVerify(use: ProfileUse, signal: PixelCameraStateSignal, action: AutomationAction) {
        if (!observer.matches(signal, use.profile, observer.readNodes())) dispatch(use, action)
        observer.awaitNodes("$signal selected") { observer.matches(signal, use.profile, it) }
    }

    private suspend fun closePanel(
        use: ProfileUse, signal: PixelCameraStateSignal,
        settingsStillValid: (List<UiNodeSnapshot>) -> Boolean = { true },
    ) {
        val nodes = observer.readNodes()
        if (!settingsStillValid(nodes)) reject("Capture settings changed before panel dismissal")
        val binding = observer.match(signal, use.profile, nodes) as? SelectorMatchResult.Match
            ?: reject("Settings panel changed before dismissal")
        when (gateway.dispatchGlobalBack(binding.node)) {
            AccessibilityDispatchResult.GlobalActionDispatched -> Unit
            else -> reject("Settings panel dismissal was rejected")
        }
        observer.awaitNodes("$signal closed") { !observer.matches(signal, use.profile, it) }
    }

    private suspend fun dispatch(use: ProfileUse, action: AutomationAction) {
        when (val result = dispatcher.dispatchValidated(use, validator, action)) {
            is ActionDispatch.Dispatched -> Unit
            is ActionDispatch.Rejected -> throw PreparationRejected(result.failure)
        }
    }

    private fun requireIdleCapture(
        capture: CaptureConfiguration, profile: PixelCameraProfile, nodes: List<UiNodeSnapshot>,
    ) {
        if (!observer.idleCaptureMatches(capture, profile, nodes)) {
            reject("Capture mode or lens changed before preparation")
        }
    }

    private fun reject(message: String): Nothing = throw PreparationRejected(
        AutomationFailure(AutomationFailureCode.CAMERA_STATE_UNKNOWN, message,
            mapOf("operation" to "PREPARE_CAPTURE")),
    )


}
