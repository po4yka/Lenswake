package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.automation.SelectorMatchResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.PixelCameraDialogKind
import dev.po4yka.lenswake.core.UiSelectorSet

internal class PixelCameraDialogRecoveryDispatcher(
    private val selectorMatcher: SelectorMatcher,
    private val gateway: PixelCameraAccessibilityGateway,
) {
    suspend fun recover(
        dialog: PixelCameraDialogKind,
        profileUse: ProfileUse,
        validator: PixelCameraProfileValidator,
    ): ActionDispatch {
        validator.validate(profileUse)?.let { return ActionDispatch.Rejected(it) }
        val rule = profileUse.profile.dialogProfiles[dialog]
            ?: return unsupported(dialog, "The profile does not recognize this Pixel Camera dialog")
        val target = rule.recoveryTarget
            ?: return unsupported(dialog, "The recognized Pixel Camera dialog has no safe recovery target")
        return when (val snapshot = gateway.snapshot()) {
            is AccessibilitySnapshotResult.Available -> if (snapshot.truncated) {
                rejected(
                    AutomationFailureCode.CAMERA_STATE_UNKNOWN,
                    "The bounded Pixel Camera accessibility snapshot was truncated",
                    dialog,
                )
            } else {
                recoverInSnapshot(dialog, rule.presence, target, profileUse, snapshot.nodes)
            }
            AccessibilitySnapshotResult.ServiceDisconnected -> rejected(
                AutomationFailureCode.ACCESSIBILITY_DISABLED,
                "Lenswake Accessibility Service is not connected",
                dialog,
            )
            AccessibilitySnapshotResult.RefreshFailed -> rejected(
                AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED,
                "The active Pixel Camera accessibility window could not be refreshed",
                dialog,
            )
            AccessibilitySnapshotResult.NoActiveWindow,
            AccessibilitySnapshotResult.PixelCameraNotForeground,
            -> rejected(
                AutomationFailureCode.PIXEL_CAMERA_NOT_FOREGROUND,
                "Pixel Camera is not the active accessibility window",
                dialog,
            )
        }
    }

    private suspend fun recoverInSnapshot(
        dialog: PixelCameraDialogKind,
        presence: UiSelectorSet,
        target: UiSelectorSet,
        profileUse: ProfileUse,
        nodes: List<UiNodeSnapshot>,
    ): ActionDispatch = when (selectorMatcher.match(presence, profileUse.profile, nodes)) {
        is SelectorMatchResult.Match -> dispatchTarget(dialog, target, profileUse, nodes)
        is SelectorMatchResult.Ambiguous -> rejected(
            AutomationFailureCode.UI_TARGET_AMBIGUOUS,
            "The Pixel Camera dialog presence signal is ambiguous",
            dialog,
        )
        is SelectorMatchResult.BelowThreshold,
        SelectorMatchResult.NoEligibleNodes,
        SelectorMatchResult.TargetNotConfigured,
        -> rejected(
            AutomationFailureCode.UI_TARGET_CHANGED,
            "The Pixel Camera dialog changed before recovery dispatch",
            dialog,
        )
    }

    private suspend fun dispatchTarget(
        dialog: PixelCameraDialogKind,
        target: UiSelectorSet,
        profileUse: ProfileUse,
        nodes: List<UiNodeSnapshot>,
    ): ActionDispatch = when (val match = selectorMatcher.match(target, profileUse.profile, nodes)) {
        is SelectorMatchResult.Match -> mapDispatch(dialog, match, gateway.dispatchClick(match.node))
        is SelectorMatchResult.Ambiguous -> rejected(
            AutomationFailureCode.UI_TARGET_AMBIGUOUS,
            "Multiple Pixel Camera dialog recovery targets matched",
            dialog,
        )
        is SelectorMatchResult.BelowThreshold -> ActionDispatch.Rejected(
            AutomationFailure(
                AutomationFailureCode.UI_TARGET_CONFIDENCE_TOO_LOW,
                "The Pixel Camera dialog recovery target confidence was below the profile threshold",
                mapOf(
                    "dialog" to dialog.name,
                    "bestScore" to match.bestScore.toString(),
                    "minimumScore" to match.minimumScore.toString(),
                ),
            ),
        )
        SelectorMatchResult.NoEligibleNodes,
        SelectorMatchResult.TargetNotConfigured,
        -> unsupported(dialog, "The Pixel Camera dialog recovery target is not available")
    }

    private fun mapDispatch(
        dialog: PixelCameraDialogKind,
        match: SelectorMatchResult.Match,
        result: AccessibilityDispatchResult,
    ): ActionDispatch = when (result) {
        AccessibilityDispatchResult.SemanticActionDispatched ->
            ActionDispatch.Dispatched(InteractionMethod.ACCESSIBILITY_ACTION, match.selectorMetadata())
        AccessibilityDispatchResult.GestureSubmitted ->
            ActionDispatch.Dispatched(InteractionMethod.ACCESSIBILITY_NODE_GESTURE, match.selectorMetadata())
        AccessibilityDispatchResult.ServiceDisconnected -> rejected(
            AutomationFailureCode.ACCESSIBILITY_DISABLED,
            "Lenswake Accessibility Service disconnected before dialog recovery dispatch",
            dialog,
        )
        AccessibilityDispatchResult.RefreshFailed -> rejected(
            AutomationFailureCode.ACCESSIBILITY_REFRESH_FAILED,
            "The active Pixel Camera accessibility window could not be refreshed before dialog recovery",
            dialog,
        )
        AccessibilityDispatchResult.TargetIdentityChanged -> rejected(
            AutomationFailureCode.UI_TARGET_CHANGED,
            "The Pixel Camera dialog recovery target changed before dispatch",
            dialog,
        )
        AccessibilityDispatchResult.TargetNotFound,
        AccessibilityDispatchResult.TargetNotEligible,
        AccessibilityDispatchResult.GestureRejected,
        AccessibilityDispatchResult.GlobalActionRejected,
        -> unsupported(dialog, "Pixel Camera rejected the configured dialog recovery target")
        AccessibilityDispatchResult.GlobalActionDispatched -> error(
            "A dialog target click cannot return a global action dispatch",
        )
    }

    private fun unsupported(dialog: PixelCameraDialogKind, message: String): ActionDispatch.Rejected =
        rejected(AutomationFailureCode.UNEXPECTED_CAMERA_DIALOG, message, dialog)

    private fun rejected(
        code: AutomationFailureCode,
        message: String,
        dialog: PixelCameraDialogKind,
    ): ActionDispatch.Rejected = ActionDispatch.Rejected(
        AutomationFailure(code, message, mapOf("dialog" to dialog.name)),
    )
}
