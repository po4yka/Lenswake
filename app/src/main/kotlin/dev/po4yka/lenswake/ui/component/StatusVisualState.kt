package dev.po4yka.lenswake.ui.component

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
