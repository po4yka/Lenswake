package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.accessibility.PixelCameraAccessibilityRuntime
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.automation.SelectorMatchResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.NormalizedPoint
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.TimeLapseSpeed

internal class PixelCameraActionDispatcher(
    private val selectorMatcher: SelectorMatcher,
    private val gateway: PixelCameraAccessibilityGateway,
) {
    suspend fun dispatchValidated(
        profileUse: ProfileUse,
        validator: PixelCameraProfileValidator,
        action: AutomationAction,
        speed: TimeLapseSpeed? = null,
    ): ActionDispatch = validator.validate(profileUse)?.let(ActionDispatch::Rejected)
        ?: dispatch(action, profileUse.profile, speed)

    private suspend fun dispatch(
        action: AutomationAction,
        profile: PixelCameraProfile,
        speed: TimeLapseSpeed?,
    ): ActionDispatch = when (val snapshot = readSnapshot()) {
        is ActionSnapshot.Rejected -> ActionDispatch.Rejected(snapshot.failure)
        is ActionSnapshot.Available -> dispatchInSnapshot(action, profile, speed, snapshot.nodes)
    }

    private suspend fun dispatchInSnapshot(
        action: AutomationAction,
        profile: PixelCameraProfile,
        speed: TimeLapseSpeed?,
        nodes: List<UiNodeSnapshot>,
    ): ActionDispatch = when (val target = resolveTarget(action, profile, speed, nodes)) {
        is TargetResolution.Node -> dispatchClick(action, profile, target.match)
        TargetResolution.Fallback -> dispatchProfileGesture(action, profile)
        is TargetResolution.Rejected -> ActionDispatch.Rejected(target.failure)
    }

    private fun resolveTarget(
        action: AutomationAction,
        profile: PixelCameraProfile,
        speed: TimeLapseSpeed?,
        nodes: List<UiNodeSnapshot>,
    ): TargetResolution = when (val match = match(action, profile, speed, nodes)) {
        is SelectorMatchResult.Match -> TargetResolution.Node(match)
        is SelectorMatchResult.Ambiguous -> TargetResolution.Rejected(
            accessibilityFailure(
                AutomationFailureCode.UI_TARGET_AMBIGUOUS,
                "Multiple equally confident Pixel Camera targets matched $action",
            ),
        )
        is SelectorMatchResult.BelowThreshold -> TargetResolution.Rejected(
            AutomationFailure(
                code = AutomationFailureCode.UI_TARGET_CONFIDENCE_TOO_LOW,
                message = "Pixel Camera target confidence was below the profile threshold",
                context = mapOf(
                    "action" to action.name,
                    "bestScore" to match.bestScore.toString(),
                    "minimumScore" to match.minimumScore.toString(),
                ),
            ),
        )
        SelectorMatchResult.NoEligibleNodes,
        SelectorMatchResult.TargetNotConfigured,
        -> TargetResolution.Fallback
    }

    private fun match(
        action: AutomationAction,
        profile: PixelCameraProfile,
        speed: TimeLapseSpeed?,
        nodes: List<UiNodeSnapshot>,
    ): SelectorMatchResult = if (action == selectSpeedAction) {
        requireNotNull(speed) { "A Time Lapse speed is required for the speed-selection action" }
        profile.speedTargets[speed]?.let { selectorMatcher.match(it, profile, nodes) }
            ?: SelectorMatchResult.TargetNotConfigured
    } else {
        selectorMatcher.match(action, profile, nodes)
    }

    private suspend fun dispatchClick(
        action: AutomationAction,
        profile: PixelCameraProfile,
        match: SelectorMatchResult.Match,
    ): ActionDispatch = when (gateway.dispatchClick(match.node)) {
        AccessibilityDispatchResult.SemanticActionDispatched -> dispatched(
            InteractionMethod.ACCESSIBILITY_ACTION,
            match.selectorMetadata(),
        )
        AccessibilityDispatchResult.GestureSubmitted -> dispatched(
            InteractionMethod.ACCESSIBILITY_NODE_GESTURE,
            match.selectorMetadata(),
        )
        AccessibilityDispatchResult.GlobalActionDispatched -> error(
            "A node click cannot return a global action dispatch",
        )
        AccessibilityDispatchResult.ServiceDisconnected -> rejected(
            AutomationFailureCode.ACCESSIBILITY_DISABLED,
            "Lenswake Accessibility Service disconnected before dispatch",
        )
        AccessibilityDispatchResult.RefreshFailed -> rejected(
            AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED,
            "The active Pixel Camera accessibility window could not be refreshed before dispatch",
        )
        AccessibilityDispatchResult.TargetIdentityChanged -> ActionDispatch.Rejected(
            AutomationFailure(
                code = AutomationFailureCode.UI_TARGET_CHANGED,
                message = "The selected Pixel Camera target changed before dispatch",
                context = mapOf("action" to action.name),
            ),
        )
        AccessibilityDispatchResult.GestureRejected -> dispatchProfileGesture(action, profile)
        AccessibilityDispatchResult.TargetNotFound,
        AccessibilityDispatchResult.TargetNotEligible,
        AccessibilityDispatchResult.GlobalActionRejected,
        -> ActionDispatch.Rejected(missingActionFailure(action))
    }

    private suspend fun dispatchProfileGesture(
        action: AutomationAction,
        profile: PixelCameraProfile,
    ): ActionDispatch = profile.fallbackGestures[action]?.let { gesture ->
        mapProfileGestureResult(action, gateway.dispatchProfileGesture(gesture.point))
    } ?: ActionDispatch.Rejected(missingActionFailure(action))

    private fun mapProfileGestureResult(
        action: AutomationAction,
        result: AccessibilityDispatchResult,
    ): ActionDispatch = when (result) {
        AccessibilityDispatchResult.GestureSubmitted -> dispatched(
            InteractionMethod.ACCESSIBILITY_PROFILE_GESTURE,
        )
        AccessibilityDispatchResult.ServiceDisconnected -> rejected(
            AutomationFailureCode.ACCESSIBILITY_DISABLED,
            "Lenswake Accessibility Service disconnected before profile gesture dispatch",
        )
        AccessibilityDispatchResult.RefreshFailed -> rejected(
            AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED,
            "The active Pixel Camera window could not be refreshed before profile gesture dispatch",
        )
        AccessibilityDispatchResult.TargetNotFound,
        AccessibilityDispatchResult.TargetNotEligible,
        -> rejected(
            AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND,
            "Pixel Camera was no longer the active window before profile gesture dispatch",
        )
        AccessibilityDispatchResult.SemanticActionDispatched,
        AccessibilityDispatchResult.GlobalActionDispatched,
        AccessibilityDispatchResult.TargetIdentityChanged,
        AccessibilityDispatchResult.GestureRejected,
        AccessibilityDispatchResult.GlobalActionRejected,
        -> ActionDispatch.Rejected(missingActionFailure(action))
    }

    private suspend fun readSnapshot(): ActionSnapshot = when (val result = gateway.snapshot()) {
        is AccessibilitySnapshotResult.Available -> if (result.truncated) {
            ActionSnapshot.Rejected(
                accessibilityFailure(
                    AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                    "The bounded Pixel Camera accessibility snapshot was truncated",
                ),
            )
        } else {
            ActionSnapshot.Available(result.nodes)
        }
        AccessibilitySnapshotResult.ServiceDisconnected -> ActionSnapshot.Rejected(
            accessibilityFailure(
                AutomationFailureCode.ACCESSIBILITY_DISABLED,
                "Lenswake Accessibility Service is not connected",
            ),
        )
        AccessibilitySnapshotResult.RefreshFailed -> ActionSnapshot.Rejected(
            accessibilityFailure(
                AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED,
                "The active Pixel Camera accessibility window could not be refreshed",
            ),
        )
        AccessibilitySnapshotResult.NoActiveWindow,
        AccessibilitySnapshotResult.PixelCameraNotForeground,
        -> ActionSnapshot.Rejected(
            accessibilityFailure(
                AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND,
                "Pixel Camera is not the active accessibility window",
            ),
        )
    }
}

private sealed interface TargetResolution {
    data class Node(val match: SelectorMatchResult.Match) : TargetResolution

    data class Rejected(val failure: AutomationFailure) : TargetResolution

    data object Fallback : TargetResolution
}

private sealed interface ActionSnapshot {
    data class Available(val nodes: List<UiNodeSnapshot>) : ActionSnapshot

    data class Rejected(val failure: AutomationFailure) : ActionSnapshot
}

private fun dispatched(
    method: InteractionMethod,
    metadata: Map<String, String> = emptyMap(),
): ActionDispatch = ActionDispatch.Dispatched(method, metadata)

internal fun SelectorMatchResult.Match.selectorMetadata(): Map<String, String> = mapOf(
    "selectorScore" to score.toString(),
    "selectorMinimumScore" to minimumScore.toString(),
    "selectorIndex" to selectorIndex.toString(),
    "selectorSignals" to matchedSignals.map { it.name }.sorted().joinToString(","),
)

private fun rejected(
    code: AutomationFailureCode,
    message: String,
): ActionDispatch = ActionDispatch.Rejected(accessibilityFailure(code, message))

internal interface PixelCameraAccessibilityGateway {
    suspend fun snapshot(): AccessibilitySnapshotResult

    suspend fun dispatchClick(node: UiNodeSnapshot): AccessibilityDispatchResult

    suspend fun dispatchProfileGesture(point: NormalizedPoint): AccessibilityDispatchResult

    suspend fun dispatchGlobalBack(pickerNode: UiNodeSnapshot): AccessibilityDispatchResult
}

internal object RuntimePixelCameraAccessibilityGateway : PixelCameraAccessibilityGateway {
    override suspend fun snapshot(): AccessibilitySnapshotResult =
        PixelCameraAccessibilityRuntime.snapshot()

    override suspend fun dispatchClick(node: UiNodeSnapshot): AccessibilityDispatchResult =
        PixelCameraAccessibilityRuntime.dispatchClick(node)

    override suspend fun dispatchProfileGesture(point: NormalizedPoint): AccessibilityDispatchResult =
        PixelCameraAccessibilityRuntime.dispatchProfileGesture(point)

    override suspend fun dispatchGlobalBack(pickerNode: UiNodeSnapshot): AccessibilityDispatchResult =
        PixelCameraAccessibilityRuntime.dispatchGlobalBack(pickerNode)
}
