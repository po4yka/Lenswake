package dev.po4yka.lenswake.ui.component

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal enum class StatusVisualState {
    ERROR,
    WARNING,
    SUCCESS,
    IN_PROGRESS,
    NEUTRAL,
}

internal fun statusVisualState(statusLabel: String): StatusVisualState {
    val normalizedStatus = statusLabel.lowercase()
    return when {
        listOf("blocked", "failed", "error", "incompatible", "unavailable", "cancelled")
            .any(normalizedStatus::contains) -> StatusVisualState.ERROR
        listOf("warning", "attention", "needs", "safety", "pending", "degraded", "stale")
            .any(normalizedStatus::contains) -> StatusVisualState.WARNING
        listOf("ready", "available", "verified", "completed", "passed", "enabled", "succeeded")
            .any(normalizedStatus::contains) -> StatusVisualState.SUCCESS
        listOf("checking", "installing", "running", "working", "in progress")
            .any(normalizedStatus::contains) -> StatusVisualState.IN_PROGRESS
        else -> StatusVisualState.NEUTRAL
    }
}

/**
 * Accent for a status label that is drawn straight on a surface.
 *
 * The `on*` roles of the status visuals are legible only on their matching filled indicator.
 * These base roles are the Material 3 roles that hold at least 4.5:1 against a surface, in the
 * baseline schemes and under dynamic color.
 */
internal fun StatusVisualState.onSurfaceAccent(colorScheme: ColorScheme): Color = when (this) {
    StatusVisualState.ERROR -> colorScheme.error
    StatusVisualState.WARNING -> colorScheme.tertiary
    StatusVisualState.SUCCESS -> colorScheme.primary
    StatusVisualState.IN_PROGRESS -> colorScheme.secondary
    StatusVisualState.NEUTRAL -> colorScheme.onSurfaceVariant
}
