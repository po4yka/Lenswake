package dev.po4yka.lenswake.ui

import androidx.compose.runtime.Immutable
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Immutable
data class LenswakeUiState(
    val readiness: ReadinessUiState = ReadinessUiState.Checking(
        title = "",
        summary = "",
    ),
    val schedules: List<ScheduleSummaryUiState> = emptyList(),
    val profiles: List<ProfileSummaryUiState> = emptyList(),
    val capabilities: List<CapabilityUiState> = emptyList(),
    val diagnosticEvents: List<DiagnosticEventUiState> = emptyList(),
    val alarmTransportIncidents: List<AlarmTransportIncidentUiState> = emptyList(),
    val profilePersistenceIssues: List<ProfilePersistenceIssueUiState> = emptyList(),
    val profileInstall: ProfileInstallUiState = ProfileInstallUiState.Idle,
    val rehearsal: RehearsalActionUiState = RehearsalActionUiState.Idle,
    val rehearsalTarget: RehearsalTargetUiState? = null,
    val activeSession: ActiveSessionUiState? = null,
    val scheduleEditor: ScheduleEditorUiState = ScheduleEditorUiState.Closed,
    val scheduleAction: ScheduleActionUiState = ScheduleActionUiState.Idle,
    val pendingDeleteScheduleId: String? = null,
    val actions: UiActionAvailability = UiActionAvailability(),
    val setupRemediationMessage: String? = null,
)

@Immutable
data class ActiveSessionUiState(
    val sessionId: String,
    val kind: ActiveSessionKind,
    val stopDeadline: Instant,
    val title: String,
    val detail: String,
    val status: String,
)

enum class ActiveSessionKind {
    SCHEDULED,
    REHEARSAL,
}

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
    val captureMode: CaptureMode = CaptureMode.TIME_LAPSE,
    val timeLapseSpeed: TimeLapseSpeed = TimeLapseSpeed.X120,
    val lens: LensSelection = LensSelection.REAR_MAIN,
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
        val sessionId: String,
        val message: String,
    ) : RehearsalActionUiState
}

@Immutable
sealed interface RehearsalTargetUiState {
    @Immutable
    data class Profile(val profileId: String) : RehearsalTargetUiState

    @Immutable
    data class Schedule(val scheduleId: String) : RehearsalTargetUiState
}

@Immutable
sealed interface ReadinessUiState {
    val title: String
    val summary: String

    @Immutable
    data class Checking(
        override val title: String,
        override val summary: String,
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
    val capture: CaptureConfiguration,
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
    val supportedCaptures: Set<CaptureConfiguration>,
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

@Immutable
data class ProfilePersistenceIssueUiState(
    val id: String,
    val title: String,
    val detail: String,
)

enum class AlarmTransportIncidentUiAction {
    OPEN_PIXEL_CAMERA,
}

@Immutable
data class UiActionAvailability(
    val canCreateSchedule: Boolean = false,
    val createScheduleUnavailableReason: String = "",
    val canInstallCandidateProfile: Boolean = false,
    val installCandidateProfileUnavailableReason: String = "",
    val canRunRehearsal: Boolean = false,
    val rehearsalUnavailableReason: String = "",
    val canExportDiagnostics: Boolean = false,
    val exportDiagnosticsUnavailableReason: String = "",
)
