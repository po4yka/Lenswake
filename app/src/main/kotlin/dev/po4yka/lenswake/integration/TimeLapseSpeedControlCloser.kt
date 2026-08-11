package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.PixelCameraState
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.SelectorMatchResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.TimeLapseSpeed

internal class TimeLapseSpeedControlCloser(
    private val selectorMatcher: SelectorMatcher,
    private val gateway: PixelCameraAccessibilityGateway,
    private val stateInferer: PixelCameraStateInferer,
) {
    suspend fun close(
        expectedSpeed: TimeLapseSpeed?,
        profile: PixelCameraProfile,
    ): ActionDispatch = when (val snapshot = readSnapshot()) {
        is CloseSnapshot.Rejected -> ActionDispatch.Rejected(snapshot.failure)
        is CloseSnapshot.Available -> closeFromSnapshot(expectedSpeed, profile, snapshot.nodes)
    }

    private suspend fun closeFromSnapshot(
        expectedSpeed: TimeLapseSpeed?,
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): ActionDispatch = when (val state = stateInferer.infer(profile, nodes)) {
        is PortResult.Unavailable -> ActionDispatch.Rejected(state.failure)
        is PortResult.Observed -> when (val picker = validatePicker(state.value, expectedSpeed)) {
            is PickerValidation.Rejected -> ActionDispatch.Rejected(picker.failure)
            PickerValidation.Valid -> bindAndClosePicker(profile, nodes)
        }
    }

    private fun validatePicker(
        state: PixelCameraState,
        expectedSpeed: TimeLapseSpeed?,
    ): PickerValidation = if (state is PixelCameraState.TimeLapseSpeedPicker) {
        val selectionMatches = expectedSpeed == null || state.speed == expectedSpeed
        if (!state.recording && selectionMatches) {
            PickerValidation.Valid
        } else {
            PickerValidation.Rejected(changedPickerFailure())
        }
    } else {
        PickerValidation.Rejected(changedPickerFailure())
    }

    private suspend fun bindAndClosePicker(
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): ActionDispatch = when (val picker = bindPicker(profile, nodes)) {
        is PickerBinding.Bound -> mapGlobalBack(picker.match, gateway.dispatchGlobalBack(picker.match.node))
        is PickerBinding.Rejected -> ActionDispatch.Rejected(picker.failure)
    }

    private fun bindPicker(
        profile: PixelCameraProfile,
        nodes: List<UiNodeSnapshot>,
    ): PickerBinding = profile.stateSignals[PixelCameraStateSignal.TIME_LAPSE_SPEED_PICKER_OPEN]
        ?.let { selectorMatcher.match(it, profile, nodes) }
        ?.let { match ->
            when (match) {
                is SelectorMatchResult.Match -> PickerBinding.Bound(match)
                is SelectorMatchResult.Ambiguous,
                is SelectorMatchResult.BelowThreshold,
                SelectorMatchResult.NoEligibleNodes,
                SelectorMatchResult.TargetNotConfigured,
                -> PickerBinding.Rejected(unboundPickerFailure())
            }
        } ?: PickerBinding.Rejected(
        accessibilityFailure(
            AutomationFailureCode.TIME_LAPSE_SPEED_CONTROL_CLOSE_FAILED,
            "The profile does not define an observable Time Lapse speed picker",
        ),
    )

    private fun mapGlobalBack(
        match: SelectorMatchResult.Match,
        result: AccessibilityDispatchResult,
    ): ActionDispatch = when (result) {
        AccessibilityDispatchResult.GlobalActionDispatched -> ActionDispatch.Dispatched(
            InteractionMethod.ACCESSIBILITY_ACTION,
            match.selectorMetadata(),
        )
        AccessibilityDispatchResult.ServiceDisconnected -> rejectedClose(
            AutomationFailureCode.ACCESSIBILITY_DISABLED,
            "Lenswake Accessibility Service disconnected before closing the speed picker",
        )
        AccessibilityDispatchResult.RefreshFailed -> rejectedClose(
            AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED,
            "The active Pixel Camera window changed before closing the speed picker",
        )
        AccessibilityDispatchResult.TargetNotFound,
        AccessibilityDispatchResult.TargetNotEligible,
        -> rejectedClose(
            AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND,
            "Pixel Camera was no longer the active window before closing the speed picker",
        )
        AccessibilityDispatchResult.SemanticActionDispatched,
        AccessibilityDispatchResult.GestureSubmitted,
        AccessibilityDispatchResult.TargetIdentityChanged,
        AccessibilityDispatchResult.GestureRejected,
        AccessibilityDispatchResult.GlobalActionRejected,
        -> rejectedClose(
            AutomationFailureCode.TIME_LAPSE_SPEED_CONTROL_CLOSE_FAILED,
            "Android rejected the global Back action for the Time Lapse speed picker",
        )
    }

    private suspend fun readSnapshot(): CloseSnapshot = when (val result = gateway.snapshot()) {
        is AccessibilitySnapshotResult.Available -> if (result.truncated) {
            CloseSnapshot.Rejected(
                accessibilityFailure(
                    AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                    "The bounded Pixel Camera accessibility snapshot was truncated",
                ),
            )
        } else {
            CloseSnapshot.Available(result.nodes)
        }
        AccessibilitySnapshotResult.ServiceDisconnected -> CloseSnapshot.Rejected(
            accessibilityFailure(
                AutomationFailureCode.ACCESSIBILITY_DISABLED,
                "Lenswake Accessibility Service is not connected",
            ),
        )
        AccessibilitySnapshotResult.RefreshFailed -> CloseSnapshot.Rejected(
            accessibilityFailure(
                AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED,
                "The active Pixel Camera accessibility window could not be refreshed",
            ),
        )
        AccessibilitySnapshotResult.NoActiveWindow,
        AccessibilitySnapshotResult.PixelCameraNotForeground,
        -> CloseSnapshot.Rejected(
            accessibilityFailure(
                AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND,
                "Pixel Camera is not the active accessibility window",
            ),
        )
    }
}

private sealed interface CloseSnapshot {
    data class Available(val nodes: List<UiNodeSnapshot>) : CloseSnapshot

    data class Rejected(val failure: AutomationFailure) : CloseSnapshot
}

private sealed interface PickerValidation {
    data object Valid : PickerValidation

    data class Rejected(val failure: AutomationFailure) : PickerValidation
}

private sealed interface PickerBinding {
    data class Bound(val match: SelectorMatchResult.Match) : PickerBinding

    data class Rejected(val failure: AutomationFailure) : PickerBinding
}

private fun changedPickerFailure(): AutomationFailure = accessibilityFailure(
    AutomationFailureCode.TIME_LAPSE_SPEED_CONTROL_CLOSE_FAILED,
    "A fresh Pixel Camera snapshot did not confirm the selected Time Lapse speed picker",
)

private fun unboundPickerFailure(): AutomationFailure = accessibilityFailure(
    AutomationFailureCode.TIME_LAPSE_SPEED_CONTROL_CLOSE_FAILED,
    "The open Time Lapse speed picker could not be bound to a fresh UI node",
)

private fun rejectedClose(
    code: AutomationFailureCode,
    message: String,
): ActionDispatch = ActionDispatch.Rejected(accessibilityFailure(code, message))
