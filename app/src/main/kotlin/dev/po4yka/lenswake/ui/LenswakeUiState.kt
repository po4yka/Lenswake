package dev.po4yka.lenswake.ui

import androidx.compose.runtime.Immutable

@Immutable
data class LenswakeUiState(
    val readiness: ReadinessUiState = ReadinessUiState.Blocked(
        title = "Setup required",
        summary = "Scheduling is disabled until exact alarms, Accessibility, and a verified Pixel Camera profile are available.",
    ),
    val schedules: List<ScheduleSummaryUiState> = emptyList(),
    val profiles: List<ProfileSummaryUiState> = emptyList(),
    val capabilities: List<CapabilityUiState> = defaultCapabilities,
    val diagnosticEvents: List<DiagnosticEventUiState> = emptyList(),
    val profileInstall: ProfileInstallUiState = ProfileInstallUiState.Idle,
    val rehearsal: RehearsalActionUiState = RehearsalActionUiState.Idle,
    val scheduleEditor: ScheduleEditorUiState = ScheduleEditorUiState.Closed,
    val scheduleAction: ScheduleActionUiState = ScheduleActionUiState.Idle,
    val pendingDeleteScheduleId: String? = null,
    val actions: UiActionAvailability = UiActionAvailability(),
)

@Immutable
sealed interface ScheduleEditorUiState {
    @Immutable
    data object Closed : ScheduleEditorUiState

    @Immutable
    data class Open(
        val mode: ScheduleEditorMode,
        val form: ScheduleFormUiState,
        val error: String? = null,
    ) : ScheduleEditorUiState
}

@Immutable
sealed interface ScheduleEditorMode {
    @Immutable
    data object Create : ScheduleEditorMode

    @Immutable
    data class Edit(val scheduleId: String) : ScheduleEditorMode
}

@Immutable
data class ScheduleFormUiState(
    val name: String = "",
    val startLocal: String = "",
    val stopLocal: String = "",
    val zoneId: String = "",
    val profileId: String = "",
    val enabled: Boolean = true,
)

@Immutable
sealed interface ScheduleActionUiState {
    @Immutable
    data object Idle : ScheduleActionUiState

    @Immutable
    data class Working(val message: String) : ScheduleActionUiState

    @Immutable
    data class Succeeded(val message: String) : ScheduleActionUiState

    @Immutable
    data class Failed(
        val message: String,
        val rollbackFailures: List<String> = emptyList(),
    ) : ScheduleActionUiState
}

@Immutable
sealed interface ProfileInstallUiState {
    @Immutable
    data object Idle : ProfileInstallUiState

    @Immutable
    data object Installing : ProfileInstallUiState

    @Immutable
    data class Succeeded(
        val message: String,
    ) : ProfileInstallUiState

    @Immutable
    data class Failed(
        val message: String,
    ) : ProfileInstallUiState
}

@Immutable
sealed interface RehearsalActionUiState {
    @Immutable
    data object Idle : RehearsalActionUiState

    @Immutable
    data object Running : RehearsalActionUiState

    @Immutable
    data class Passed(
        val message: String,
    ) : RehearsalActionUiState

    @Immutable
    data class Failed(
        val message: String,
    ) : RehearsalActionUiState

    @Immutable
    data class SafetyStopPending(
        val message: String,
    ) : RehearsalActionUiState
}

@Immutable
sealed interface ReadinessUiState {
    val title: String
    val summary: String

    @Immutable
    data class Checking(
        override val title: String = "Checking readiness",
        override val summary: String = "Lenswake has not finished checking this device.",
    ) : ReadinessUiState

    @Immutable
    data class Ready(
        override val title: String,
        override val summary: String,
    ) : ReadinessUiState

    @Immutable
    data class ReadyWithWarnings(
        override val title: String,
        override val summary: String,
        val warnings: List<String>,
    ) : ReadinessUiState

    @Immutable
    data class Blocked(
        override val title: String,
        override val summary: String,
    ) : ReadinessUiState
}

@Immutable
data class CapabilityUiState(
    val name: String,
    val status: CapabilityStatus,
    val detail: String,
    val required: Boolean,
)

enum class CapabilityStatus {
    UNKNOWN,
    AVAILABLE,
    BLOCKED,
}

@Immutable
data class ScheduleSummaryUiState(
    val id: String,
    val title: String,
    val timing: String,
    val status: String,
    val startLocal: String,
    val stopLocal: String,
    val zoneId: String,
    val profileId: String,
    val enabled: Boolean,
)

@Immutable
data class ProfileSummaryUiState(
    val id: String,
    val title: String,
    val environment: String,
    val compatibility: String,
    val verifiedForScheduling: Boolean,
)

@Immutable
data class DiagnosticEventUiState(
    val id: String,
    val title: String,
    val detail: String,
    val occurredAt: String,
)

@Immutable
data class UiActionAvailability(
    val canCreateSchedule: Boolean = false,
    val createScheduleUnavailableReason: String =
        "Current readiness and an exact rehearsed Pixel Camera profile have not been verified.",
    val canInstallCandidateProfile: Boolean = false,
    val installCandidateProfileUnavailableReason: String = "Candidate profile availability has not been checked.",
    val canRunRehearsal: Boolean = false,
    val rehearsalUnavailableReason: String =
        "A matching profile and the required screen-on rehearsal capabilities are not ready.",
    val canExportDiagnostics: Boolean = false,
    val exportDiagnosticsUnavailableReason: String = "There are no diagnostic events to export.",
)

private val defaultCapabilities = listOf(
    CapabilityUiState(
        name = "Exact alarms",
        status = CapabilityStatus.UNKNOWN,
        detail = "Exact-alarm access has not been checked.",
        required = true,
    ),
    CapabilityUiState(
        name = "Device wake",
        status = CapabilityStatus.BLOCKED,
        detail = "No verified device-wake implementation is configured.",
        required = true,
    ),
    CapabilityUiState(
        name = "Lenswake Accessibility Service",
        status = CapabilityStatus.BLOCKED,
        detail = "The service is not enabled or has not been verified.",
        required = true,
    ),
    CapabilityUiState(
        name = "Pixel Camera profile",
        status = CapabilityStatus.BLOCKED,
        detail = "No verified profile is configured for this environment.",
        required = true,
    ),
    CapabilityUiState(
        name = "Physical-device rehearsal",
        status = CapabilityStatus.BLOCKED,
        detail = "No successful rehearsal result exists for this environment.",
        required = true,
    ),
    CapabilityUiState(
        name = "Privileged fallback",
        status = CapabilityStatus.UNKNOWN,
        detail = "Optional privileged capabilities have not been checked.",
        required = false,
    ),
)
