package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.automation.SelectorMatchResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Rear zoom controls must never be applied to the front camera's identically labelled zoom. */
internal class PixelCameraLensSelector(
    private val matcher: SelectorMatcher,
    private val gateway: PixelCameraAccessibilityGateway,
    private val validator: PixelCameraProfileValidator,
) {
    private val dispatcher = PixelCameraActionDispatcher(matcher, gateway)

    suspend fun select(lens: LensSelection, use: ProfileUse): ActionDispatch =
        validator.validate(use)?.let(ActionDispatch::Rejected) ?: selectValidated(lens, use)

    private suspend fun selectValidated(lens: LensSelection, use: ProfileUse): ActionDispatch =
        if (lens != LensSelection.FRONT && hasSignal(PixelCameraStateSignal.FRONT_LENS_ACTIVE, use)) {
            when (val switch = dispatcher.dispatchValidated(use, validator, AutomationAction.SELECT_REAR_MAIN_LENS)) {
                is ActionDispatch.Rejected -> switch
                is ActionDispatch.Dispatched -> selectAfterFacingSwitch(lens, use, switch)
            }
        } else {
            dispatcher.dispatchValidated(use, validator, lensActions.getValue(lens))
        }

    private suspend fun selectAfterFacingSwitch(
        lens: LensSelection, use: ProfileUse, switch: ActionDispatch.Dispatched,
    ): ActionDispatch {
        val rearConfirmed = withTimeoutOrNull(FACING_TIMEOUT_MS) {
            while (!hasSignal(PixelCameraStateSignal.REAR_CAMERA_ACTIVE, use)) delay(OBSERVATION_INTERVAL_MS)
            true
        } == true
        return when {
            !rearConfirmed -> ActionDispatch.Rejected(AutomationFailure(
                AutomationFailureCode.LENS_NOT_VERIFIED,
                "Pixel Camera did not confirm the rear camera before zoom selection",
            ))
            hasSignal(lensSignals.entries.single { it.value == lens }.key, use) -> switch
            else -> dispatcher.dispatchValidated(use, validator, lensActions.getValue(lens))
        }
    }

    private suspend fun hasSignal(signal: PixelCameraStateSignal, use: ProfileUse): Boolean {
        val snapshot = gateway.snapshot() as? AccessibilitySnapshotResult.Available ?: return false
        if (snapshot.truncated) return false
        val selector = use.profile.stateSignals[signal] ?: return false
        return matcher.match(selector, use.profile, snapshot.nodes) is SelectorMatchResult.Match
    }

    private companion object {
        const val FACING_TIMEOUT_MS = 3_000L
        const val OBSERVATION_INTERVAL_MS = 100L
    }
}
