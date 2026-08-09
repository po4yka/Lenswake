package dev.po4yka.lenswake.core

data class PreflightReport(
    val checks: List<PreflightCheck>,
) {
    val readiness: ScheduleReadiness
        get() {
            val blockers = checks.filter {
                it.severity == PreflightSeverity.BLOCKING && it.status != PreflightStatus.PASSED
            }
            if (blockers.isNotEmpty()) return ScheduleReadiness.Blocked(blockers)

            val warnings = checks.filter {
                it.severity == PreflightSeverity.WARNING && it.status != PreflightStatus.PASSED
            }
            return if (warnings.isEmpty()) {
                ScheduleReadiness.Ready
            } else {
                ScheduleReadiness.ReadyWithWarnings(warnings)
            }
        }

    val ready: Boolean
        get() = readiness !is ScheduleReadiness.Blocked
}

data class PreflightCheck(
    val type: PreflightCheckType,
    val severity: PreflightSeverity,
    val status: PreflightStatus,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "Preflight message must not be blank" }
    }
}

enum class PreflightCheckType {
    EXACT_ALARMS,
    PIXEL_CAMERA_INSTALLED,
    SECURE_CAMERA_RESOLVES,
    ACCESSIBILITY_ENABLED,
    PROFILE_AVAILABLE,
    PROFILE_COMPATIBILITY,
    REHEARSAL_CURRENT,
    PRIVILEGED_FALLBACK,
    BATTERY,
    CHARGING,
    STORAGE,
}

enum class PreflightSeverity {
    INFO,
    WARNING,
    BLOCKING,
}

enum class PreflightStatus {
    PASSED,
    FAILED,
    UNKNOWN,
}

sealed interface ScheduleReadiness {
    data object Ready : ScheduleReadiness

    data class ReadyWithWarnings(
        val warnings: List<PreflightCheck>,
    ) : ScheduleReadiness

    data class Blocked(
        val blockers: List<PreflightCheck>,
    ) : ScheduleReadiness
}
