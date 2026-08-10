package dev.po4yka.lenswake.core

data class AutomationFailure(
    val code: AutomationFailureCode,
    val message: String,
    val context: Map<String, String> = emptyMap(),
) {
    init {
        require(message.isNotBlank()) { "Failure message must not be blank" }
        require(context.size <= MAX_CONTEXT_ENTRIES) {
            "Failure context may contain at most $MAX_CONTEXT_ENTRIES entries"
        }
        require(context.all { (key, value) -> key.length <= MAX_CONTEXT_LENGTH && value.length <= MAX_CONTEXT_LENGTH }) {
            "Failure context keys and values may contain at most $MAX_CONTEXT_LENGTH characters"
        }
    }

    companion object {
        const val MAX_CONTEXT_ENTRIES: Int = 16
        const val MAX_CONTEXT_LENGTH: Int = 256
    }
}

enum class AutomationFailureCode {
    EXACT_ALARM_UNAVAILABLE,
    ACCESSIBILITY_DISABLED,
    ACCESSIBILITY_REFRESH_FAILED,
    PIXEL_CAMERA_NOT_INSTALLED,
    PIXEL_CAMERA_RESOLUTION_FAILED,
    PIXEL_CAMERA_LAUNCH_FAILED,
    PIXEL_CAMERA_NOT_FOREGROUND,
    PIXEL_CAMERA_VERSION_CHANGED,
    PROFILE_NOT_FOUND,
    PROFILE_INCOMPATIBLE,
    PROFILE_REQUIRES_REHEARSAL,
    UNSUPPORTED_CAPTURE_CONFIGURATION,
    PRIVILEGED_BRIDGE_UNAVAILABLE,
    WAKE_FAILED,
    CAMERA_STATE_UNKNOWN,
    VIDEO_MODE_NOT_FOUND,
    VIDEO_MODE_NOT_VERIFIED,
    TIME_LAPSE_MODE_NOT_FOUND,
    TIME_LAPSE_MODE_NOT_VERIFIED,
    TIME_LAPSE_SPEED_NOT_FOUND,
    TIME_LAPSE_SPEED_NOT_VERIFIED,
    LENS_NOT_FOUND,
    LENS_NOT_VERIFIED,
    RECORD_CONTROL_NOT_FOUND,
    RECORD_ACTION_FAILED,
    RECORDING_NOT_CONFIRMED,
    STOP_CONTROL_NOT_FOUND,
    STOP_ACTION_FAILED,
    STOP_NOT_CONFIRMED,
    UNEXPECTED_CAMERA_DIALOG,
    AUTOMATION_TIMEOUT,
    SESSION_STATE_CONFLICT,
    UI_TARGET_AMBIGUOUS,
    UI_TARGET_CONFIDENCE_TOO_LOW,
    SESSION_PERSISTENCE_FAILED,
    RUNTIME_READINESS_FAILED,
    DEVICE_REBOOT_INTERRUPTED,
    AUTOMATION_CANCELLED,
    UNKNOWN,
}
