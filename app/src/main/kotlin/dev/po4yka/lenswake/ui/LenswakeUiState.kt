package dev.po4yka.lenswake.ui

import androidx.compose.runtime.Immutable
import dev.po4yka.lenswake.core.SetupRemediationAction
import java.time.LocalDateTime
import java.time.ZoneId

@Immutable
data class LenswakeUiState(
    val readiness: ReadinessUiState = ReadinessUiState.Blocked(
        title = "Setup required",
        summary = "Finish device setup and test Pixel Camera before creating a schedule.",
    ),
    val schedules: List<ScheduleSummaryUiState> = emptyList(),
    val profiles: List<ProfileSummaryUiState> = emptyList(),
    val capabilities: List<CapabilityUiState> = defaultCapabilities,
    val diagnosticEvents: List<DiagnosticEventUiState> = emptyList(),
    val alarmTransportIncidents: List<AlarmTransportIncidentUiState> = emptyList(),
    val profileInstall: ProfileInstallUiState = ProfileInstallUiState.Idle,
    val rehearsal: RehearsalActionUiState = RehearsalActionUiState.Idle,
    val scheduleEditor: ScheduleEditorUiState = ScheduleEditorUiState.Closed,
    val scheduleAction: ScheduleActionUiState = ScheduleActionUiState.Idle,
    val pendingDeleteScheduleId: String? = null,
    val actions: UiActionAvailability = UiActionAvailability(),
    val setupRemediationMessage: String? = null,
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
    val startLocal: LocalDateTime? = null,
    val stopLocal: LocalDateTime? = null,
    val zoneId: ZoneId = ZoneId.systemDefault(),
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
    val remediation: SetupRemediationAction? = null,
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
    val startLocal: LocalDateTime,
    val stopLocal: LocalDateTime,
    val zoneId: ZoneId,
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
data class AlarmTransportIncidentUiState(
    val id: String,
    val title: String,
    val detail: String,
    val occurredAt: String,
    val action: AlarmTransportIncidentUiAction? = null,
)

enum class AlarmTransportIncidentUiAction {
    OPEN_PIXEL_CAMERA,
}

@Immutable
data class UiActionAvailability(
    val canCreateSchedule: Boolean = false,
    val createScheduleUnavailableReason: String =
        "Finish Setup and test the camera profile before creating a schedule.",
    val canInstallCandidateProfile: Boolean = false,
    val installCandidateProfileUnavailableReason: String = "Camera profile availability has not been checked.",
    val canRunRehearsal: Boolean = false,
    val rehearsalUnavailableReason: String =
        "Install a matching camera profile and finish the required Setup checks first.",
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
        name = "Camera test",
        status = CapabilityStatus.BLOCKED,
        detail = "Run a successful test recording on this device.",
        required = true,
    ),
    CapabilityUiState(
        name = "Privileged fallback",
        status = CapabilityStatus.UNKNOWN,
        detail = "Optional privileged capabilities have not been checked.",
        required = false,
    ),
)
