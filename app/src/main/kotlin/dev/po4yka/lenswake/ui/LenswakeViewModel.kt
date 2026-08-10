package dev.po4yka.lenswake.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfile
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfileResult
import dev.po4yka.lenswake.application.AlarmTransportIncident
import dev.po4yka.lenswake.application.AlarmTransportIncidentAction
import dev.po4yka.lenswake.application.AlarmTransportIncidentSource
import dev.po4yka.lenswake.application.EmptyAlarmTransportIncidentSource
import dev.po4yka.lenswake.application.RehearsalCoordinator
import dev.po4yka.lenswake.application.RehearsalResult
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.application.ScheduleCommand
import dev.po4yka.lenswake.application.ScheduleOperation
import dev.po4yka.lenswake.application.ScheduleWorkflow
import dev.po4yka.lenswake.application.ScheduleWorkflowResult
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.ScheduleReadiness
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.di.ApplicationGraph
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LenswakeViewModel internal constructor(
    private val scheduleRepository: ScheduleRepository,
    private val profileRepository: AutomationProfileRepository,
    executionRepository: ExecutionRepository,
    private val runtimePreflightProbe: RuntimePreflightProbe,
    private val installKnownPixelCameraProfile: InstallKnownPixelCameraProfile,
    private val rehearsalCoordinator: RehearsalCoordinator,
    private val scheduleWorkflow: ScheduleWorkflow,
    alarmTransportIncidentSource: AlarmTransportIncidentSource = EmptyAlarmTransportIncidentSource,
) : ViewModel() {
    private val preflightRefresh = MutableStateFlow(0L)
    private val profileInstall = MutableStateFlow<ProfileInstallUiState>(ProfileInstallUiState.Idle)
    private val rehearsal = MutableStateFlow<RehearsalActionUiState>(RehearsalActionUiState.Idle)
    private val scheduleEditor = MutableStateFlow<ScheduleEditorUiState>(ScheduleEditorUiState.Closed)
    private val scheduleAction = MutableStateFlow<ScheduleActionUiState>(ScheduleActionUiState.Idle)
    private val pendingDeleteScheduleId = MutableStateFlow<String?>(null)
    private val setupRemediationMessage = MutableStateFlow<String?>(null)
    private val profiles = profileRepository.observeProfiles()
    private val preflightInvalidations = merge(
        preflightRefresh.map { Unit },
        runtimePreflightProbe.invalidations,
    )
    private val diagnosticEvents = executionRepository.observeExecutions()
        .flatMapLatest { executions ->
            val eventFlows = executions
                .sortedByDescending { it.updatedAt }
                .take(MAX_OBSERVED_SESSIONS)
                .map { executionRepository.observeEvents(it.id) }

            if (eventFlows.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(eventFlows) { eventsBySession ->
                    eventsBySession
                        .flatMap { it }
                        .sortedWith(
                            compareByDescending<AutomationEvent> { it.timestamp }
                                .thenByDescending { it.sequence ?: -1L },
                        )
                        .take(MAX_VISIBLE_EVENTS)
                }
            }
        }
    private val alarmTransportIncidents = alarmTransportIncidentSource.incidents
    private val transientUiState = combine(
        profileInstall,
        rehearsal,
        combine(scheduleEditor, scheduleAction, pendingDeleteScheduleId, setupRemediationMessage) { editor, action, pendingDelete, remediationMessage ->
            ScheduleTransientUiState(editor, action, pendingDelete, remediationMessage)
        },
    ) { install, rehearsalAction, scheduleTransient ->
        TransientUiState(install, rehearsalAction, scheduleTransient)
    }

    val state: StateFlow<LenswakeUiState> = combine(
        scheduleRepository.observeSchedules(),
        profiles,
        combine(diagnosticEvents, alarmTransportIncidents, ::DiagnosticsUiData),
        combine(profiles, preflightInvalidations) { currentProfiles, _ ->
            runtimePreflightProbe.inspect(currentProfiles)
        },
        transientUiState,
    ) { schedules, currentProfiles, diagnostics, preflight, transientState ->
        LenswakeUiStateMapper.map(
            schedules = schedules,
            profiles = currentProfiles,
            events = diagnostics.events,
            incidents = diagnostics.incidents,
            preflight = preflight,
            profileInstall = transientState.profileInstall,
            rehearsal = transientState.rehearsal,
            scheduleEditor = transientState.schedule.editor,
            scheduleAction = transientState.schedule.action,
            pendingDeleteScheduleId = transientState.schedule.pendingDeleteScheduleId,
            setupRemediationMessage = transientState.schedule.remediationMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LenswakeUiState(),
    )

    fun refreshPreflight() {
        preflightRefresh.value += 1
    }

    fun reportSetupRemediationUnavailable(action: SetupRemediationAction) {
        setupRemediationMessage.value = "Unable to open ${action.remediationLabel}; resolve this requirement in Android system settings."
        refreshPreflight()
    }

    fun clearSetupRemediationMessage() {
        setupRemediationMessage.value = null
    }

    fun installCandidateProfile() {
        if (profileInstall.value == ProfileInstallUiState.Installing) return

        profileInstall.value = ProfileInstallUiState.Installing
        viewModelScope.launch {
            profileInstall.value = installKnownPixelCameraProfile().toUiState()
        }
    }

    fun runRehearsal() {
        if (!state.value.actions.canRunRehearsal || rehearsal.value == RehearsalActionUiState.Running) return

        rehearsal.value = RehearsalActionUiState.Running
        viewModelScope.launch {
            try {
                val profile = profileRepository.observeProfiles().first()
                    .sortedBy { it.id.value }
                    .firstOrNull()
                rehearsal.value = if (profile == null) {
                    RehearsalActionUiState.Failed("No Pixel Camera profile is available for rehearsal.")
                } else {
                    rehearsalCoordinator.run(
                        RehearsalRequest(
                            profileId = profile.id,
                            capture = CaptureConfiguration.TimeLapse(
                                speed = TimeLapseSpeed.X120,
                                lens = LensSelection.REAR_MAIN,
                            ),
                            recordingDuration = Duration.ofSeconds(REHEARSAL_DURATION_SECONDS),
                        ),
                    ).toUiState()
                }
                refreshPreflight()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                rehearsal.value = RehearsalActionUiState.Failed(
                    "Rehearsal failed unexpectedly: ${failure.javaClass.simpleName}.",
                )
                refreshPreflight()
            }
        }
    }

    fun beginCreateSchedule() {
        if (!state.value.actions.canCreateSchedule) {
            scheduleAction.value = ScheduleActionUiState.Failed(
                state.value.actions.createScheduleUnavailableReason,
            )
            return
        }
        val profile = state.value.profiles.firstOrNull { it.verifiedForScheduling }
        if (profile == null) {
            scheduleAction.value = ScheduleActionUiState.Failed(
                "Install the exact Pixel Camera profile before creating a schedule.",
            )
            return
        }
        scheduleAction.value = ScheduleActionUiState.Idle
        scheduleEditor.value = ScheduleEditorUiState.Open(
            mode = ScheduleEditorMode.Create,
            form = ScheduleFormUiState(
                profileId = profile.id,
                zoneId = ZoneId.systemDefault().id,
            ),
        )
    }

    fun beginEditSchedule(scheduleId: String) {
        val schedule = state.value.schedules.firstOrNull { it.id == scheduleId }
        if (schedule == null) {
            scheduleAction.value = ScheduleActionUiState.Failed("The selected schedule no longer exists.")
            return
        }
        scheduleAction.value = ScheduleActionUiState.Idle
        scheduleEditor.value = ScheduleEditorUiState.Open(
            mode = ScheduleEditorMode.Edit(scheduleId),
            form = ScheduleFormUiState(
                name = schedule.title,
                startLocal = schedule.startLocal,
                stopLocal = schedule.stopLocal,
                zoneId = schedule.zoneId,
                profileId = schedule.profileId,
                enabled = schedule.enabled,
            ),
        )
    }

    fun updateScheduleForm(form: ScheduleFormUiState) {
        val editor = scheduleEditor.value as? ScheduleEditorUiState.Open ?: return
        scheduleEditor.value = editor.copy(form = form, error = null)
    }

    fun cancelScheduleEditor() {
        if (scheduleAction.value is ScheduleActionUiState.Working) return
        scheduleEditor.value = ScheduleEditorUiState.Closed
    }

    fun submitSchedule() {
        val editor = scheduleEditor.value as? ScheduleEditorUiState.Open ?: return
        if (scheduleAction.value is ScheduleActionUiState.Working) return
        val command = editor.form.toCommandOrNull()
        if (command == null) {
            scheduleEditor.value = editor.copy(
                error = "Enter valid local timestamps as YYYY-MM-DDTHH:MM and a valid IANA time zone.",
            )
            return
        }
        launchScheduleMutation("Saving schedule…") {
            when (val mode = editor.mode) {
                ScheduleEditorMode.Create -> scheduleWorkflow.create(command)
                is ScheduleEditorMode.Edit -> scheduleWorkflow.edit(ScheduleId(mode.scheduleId), command)
            }
        }
    }

    fun setScheduleEnabled(scheduleId: String, enabled: Boolean) {
        launchScheduleMutation(if (enabled) "Enabling schedule…" else "Disabling schedule…") {
            scheduleWorkflow.setEnabled(ScheduleId(scheduleId), enabled)
        }
    }

    fun requestDeleteSchedule(scheduleId: String) {
        if (scheduleAction.value is ScheduleActionUiState.Working) return
        pendingDeleteScheduleId.value = scheduleId
    }

    fun cancelDeleteSchedule() {
        pendingDeleteScheduleId.value = null
    }

    fun confirmDeleteSchedule(scheduleId: String) {
        if (pendingDeleteScheduleId.value != scheduleId) return
        launchScheduleMutation("Deleting schedule…") {
            scheduleWorkflow.delete(ScheduleId(scheduleId))
        }
    }

    fun clearScheduleOutcome() {
        if (scheduleAction.value !is ScheduleActionUiState.Working) {
            scheduleAction.value = ScheduleActionUiState.Idle
        }
    }

    private fun launchScheduleMutation(
        message: String,
        operation: suspend () -> ScheduleWorkflowResult,
    ) {
        if (scheduleAction.value is ScheduleActionUiState.Working) return
        scheduleAction.value = ScheduleActionUiState.Working(message)
        viewModelScope.launch {
            try {
                val result = operation()
                scheduleAction.value = result.toUiState()
                if (result is ScheduleWorkflowResult.Applied || result is ScheduleWorkflowResult.Deleted) {
                    scheduleEditor.value = ScheduleEditorUiState.Closed
                    pendingDeleteScheduleId.value = null
                    refreshPreflight()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                scheduleAction.value = ScheduleActionUiState.Failed(
                    "Schedule operation failed unexpectedly: ${failure.javaClass.simpleName}.",
                )
            }
        }
    }

    class Factory internal constructor(
        private val scheduleRepository: ScheduleRepository,
        private val profileRepository: AutomationProfileRepository,
        private val executionRepository: ExecutionRepository,
        private val runtimePreflightProbe: RuntimePreflightProbe,
        private val installKnownPixelCameraProfile: InstallKnownPixelCameraProfile,
        private val rehearsalCoordinator: RehearsalCoordinator,
        private val scheduleWorkflow: ScheduleWorkflow,
        private val alarmTransportIncidentSource: AlarmTransportIncidentSource = EmptyAlarmTransportIncidentSource,
    ) : ViewModelProvider.Factory {
        constructor(graph: ApplicationGraph) : this(
            scheduleRepository = graph.scheduleRepository,
            profileRepository = graph.profileRepository,
            executionRepository = graph.executionRepository,
            alarmTransportIncidentSource = graph.alarmTransportIncidentSource,
            runtimePreflightProbe = graph.runtimePreflightProbe,
            installKnownPixelCameraProfile = graph.installKnownPixelCameraProfile,
            rehearsalCoordinator = graph.rehearsalCoordinator,
            scheduleWorkflow = graph.scheduleWorkflow,
        )

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LenswakeViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return LenswakeViewModel(
                scheduleRepository = scheduleRepository,
                profileRepository = profileRepository,
                executionRepository = executionRepository,
                alarmTransportIncidentSource = alarmTransportIncidentSource,
                runtimePreflightProbe = runtimePreflightProbe,
                installKnownPixelCameraProfile = installKnownPixelCameraProfile,
                rehearsalCoordinator = rehearsalCoordinator,
                scheduleWorkflow = scheduleWorkflow,
            ) as T
        }
    }

    private companion object {
        const val MAX_OBSERVED_SESSIONS = 10
        const val MAX_VISIBLE_EVENTS = 50
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val REHEARSAL_DURATION_SECONDS = 10L
    }
}

    private data class ScheduleTransientUiState(
        val editor: ScheduleEditorUiState,
        val action: ScheduleActionUiState,
        val pendingDeleteScheduleId: String?,
        val remediationMessage: String?,
)

private data class TransientUiState(
    val profileInstall: ProfileInstallUiState,
    val rehearsal: RehearsalActionUiState,
    val schedule: ScheduleTransientUiState,
)

private data class DiagnosticsUiData(
    val events: List<AutomationEvent>,
    val incidents: List<AlarmTransportIncident>,
)

private val SetupRemediationAction.remediationLabel: String
    get() = when (this) {
        SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION -> "the notification permission request"
        SetupRemediationAction.OPEN_NOTIFICATION_SETTINGS -> "notification settings"
        SetupRemediationAction.OPEN_EXACT_ALARM_SETTINGS -> "exact-alarm settings"
        SetupRemediationAction.OPEN_ACCESSIBILITY_SETTINGS -> "Accessibility settings"
        SetupRemediationAction.OPEN_FULL_SCREEN_INTENT_SETTINGS -> "full-screen intent settings"
    }

private fun ScheduleFormUiState.toCommandOrNull(): ScheduleCommand? = runCatching {
    val zone = ZoneId.of(zoneId.trim())
    ScheduleCommand(
        name = name,
        startAt = LocalDateTime.parse(startLocal.trim()).toUnambiguousInstant(zone),
        stopAt = LocalDateTime.parse(stopLocal.trim()).toUnambiguousInstant(zone),
        zoneId = zone,
        profileId = dev.po4yka.lenswake.core.ProfileId(profileId.trim()),
        enabled = enabled,
    )
}.getOrNull()

private fun LocalDateTime.toUnambiguousInstant(zoneId: ZoneId): java.time.Instant {
    val offsets = zoneId.rules.getValidOffsets(this)
    require(offsets.size == 1) { "Local time must exist exactly once in its time zone" }
    return toInstant(offsets.single())
}

private fun ScheduleWorkflowResult.toUiState(): ScheduleActionUiState = when (this) {
    is ScheduleWorkflowResult.Applied -> ScheduleActionUiState.Succeeded(
        when (operation) {
            ScheduleOperation.CREATED -> if (schedule.enabled) {
                "Schedule created and both exact START and STOP alarms were registered."
            } else {
                "Disabled schedule created; no alarms were registered."
            }
            ScheduleOperation.UPDATED -> if (schedule.enabled) {
                "Schedule updated and both exact alarms were replaced."
            } else {
                "Schedule updated in the disabled state; its alarms were cancelled."
            }
            ScheduleOperation.ENABLED -> "Schedule enabled and both exact START and STOP alarms were registered."
            ScheduleOperation.DISABLED -> "Schedule disabled and its START and STOP alarms were cancelled."
        },
    )

    is ScheduleWorkflowResult.Deleted -> ScheduleActionUiState.Succeeded(
        "Schedule deleted after its START and STOP alarms were cancelled.",
    )

    is ScheduleWorkflowResult.Rejected -> ScheduleActionUiState.Failed(
        message = "${code.name}: $message",
    )

    is ScheduleWorkflowResult.Failed -> ScheduleActionUiState.Failed(
        message = "${code.name}: $message",
        rollbackFailures = rollbackFailures,
    )
}

private fun RehearsalResult.toUiState(): RehearsalActionUiState = when (this) {
    is RehearsalResult.Completed -> RehearsalActionUiState.Passed(
        "Start and stop were verified for session ${session.id.value}; the exact profile is now verified.",
    )
    is RehearsalResult.Busy -> RehearsalActionUiState.Failed(
        "Another rehearsal is still active (${activeSessionId.value}).",
    )
    is RehearsalResult.Rejected -> RehearsalActionUiState.Failed(
        "${code.name}: $message",
    )
    is RehearsalResult.SafetyStopPending -> RehearsalActionUiState.SafetyStopPending(
        "$message Session ${sessionId.value} remains protected by its independent STOP alarm.",
    )
}

private fun InstallKnownPixelCameraProfileResult.toUiState(): ProfileInstallUiState = when (this) {
    is InstallKnownPixelCameraProfileResult.Installed -> ProfileInstallUiState.Succeeded(
        message = if (replacedExisting) {
            "Candidate profile replaced the previous catalog definition. A production rehearsal is still required."
        } else {
            "Candidate profile installed. A production rehearsal is still required."
        },
    )

    is InstallKnownPixelCameraProfileResult.AlreadyInstalled -> ProfileInstallUiState.Succeeded(
        message = "The candidate profile is already installed. A production rehearsal is still required.",
    )

    is InstallKnownPixelCameraProfileResult.UnsupportedEnvironment -> ProfileInstallUiState.Failed(
        message = "No candidate profile matches ${environment.deviceModel}, Android SDK ${environment.androidSdk}, " +
            "Pixel Camera ${environment.cameraVersionCode}, and ${environment.localeTag}.",
    )

    is InstallKnownPixelCameraProfileResult.EnvironmentUnavailable -> ProfileInstallUiState.Failed(
        message = "The Pixel Camera environment could not be inspected: ${failure.code.name} — ${failure.message}",
    )

    is InstallKnownPixelCameraProfileResult.PersistenceFailure -> ProfileInstallUiState.Failed(
        message = "Candidate profile persistence failed during ${stage.name.lowercase()}: $detail.",
    )
}

internal object LenswakeUiStateMapper {
    private val scheduleTimeFormatter = DateTimeFormatter.ofPattern(
        "MMM d, yyyy HH:mm z",
        Locale.ENGLISH,
    )
    private val eventTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun map(
        schedules: List<RecordingSchedule>,
        profiles: List<PixelCameraProfile>,
        events: List<AutomationEvent>,
        incidents: List<AlarmTransportIncident> = emptyList(),
        preflight: PreflightReport,
        profileInstall: ProfileInstallUiState = ProfileInstallUiState.Idle,
        rehearsal: RehearsalActionUiState = RehearsalActionUiState.Idle,
        scheduleEditor: ScheduleEditorUiState = ScheduleEditorUiState.Closed,
        scheduleAction: ScheduleActionUiState = ScheduleActionUiState.Idle,
        pendingDeleteScheduleId: String? = null,
        setupRemediationMessage: String? = null,
    ): LenswakeUiState = LenswakeUiState(
        readiness = readiness(preflight),
        schedules = schedules
            .sortedBy { it.startAt }
            .map(::scheduleSummary),
        profiles = profiles
            .sortedWith(compareBy({ it.environment.deviceModel }, { it.id.value }))
            .map(::profileSummary),
        capabilities = preflight.checks.map(::capability),
        diagnosticEvents = events.map(::eventSummary),
        alarmTransportIncidents = incidents.map(::incidentSummary),
        profileInstall = profileInstall,
        rehearsal = rehearsal,
        scheduleEditor = scheduleEditor,
        scheduleAction = scheduleAction,
        pendingDeleteScheduleId = pendingDeleteScheduleId,
        setupRemediationMessage = setupRemediationMessage,
        actions = UiActionAvailability(
            canCreateSchedule = preflight.hasAllScheduleChecksPassed() &&
                profiles.any { it.compatibility == ProfileCompatibility.VERIFIED && it.verifiedAt != null } &&
                scheduleAction !is ScheduleActionUiState.Working,
            createScheduleUnavailableReason = when {
                !preflight.hasAllScheduleChecksPassed() ->
                    "Resolve all required Setup checks before creating an enabled schedule."
                profiles.none { it.compatibility == ProfileCompatibility.VERIFIED && it.verifiedAt != null } ->
                    "Install and rehearse the exact Pixel Camera profile before creating a schedule."
                scheduleAction is ScheduleActionUiState.Working -> "A schedule operation is already in progress."
                else -> "Schedule creation is available."
            },
            canInstallCandidateProfile = profileInstall !is ProfileInstallUiState.Installing &&
                profileInstall !is ProfileInstallUiState.Succeeded,
            installCandidateProfileUnavailableReason = when {
                profileInstall is ProfileInstallUiState.Installing -> "Candidate profile installation is in progress."
                profileInstall is ProfileInstallUiState.Succeeded -> "The candidate profile was installed."
                profiles.isEmpty() -> "Candidate installation can be retried."
                else -> "The installed profile can be checked against the current catalog definition."
            },
            canRunRehearsal = canRunRehearsal(profiles, preflight, rehearsal),
            rehearsalUnavailableReason = rehearsalUnavailableReason(profiles, preflight, rehearsal),
            canExportDiagnostics = false,
            exportDiagnosticsUnavailableReason = if (events.isEmpty()) {
                "There are no diagnostic events to export."
            } else {
                "Diagnostic export is not implemented yet."
            },
        ),
    )

    private fun canRunRehearsal(
        profiles: List<PixelCameraProfile>,
        preflight: PreflightReport,
        rehearsal: RehearsalActionUiState,
    ): Boolean = profiles.isNotEmpty() &&
        rehearsal !is RehearsalActionUiState.Running &&
        rehearsal !is RehearsalActionUiState.SafetyStopPending &&
        rehearsalRequiredChecks.all { type ->
            preflight.checks.singleOrNull { it.type == type }?.status == PreflightStatus.PASSED
        }

    private fun PreflightReport.hasAllScheduleChecksPassed(): Boolean =
        scheduleRequiredChecks.all { type ->
            checks.singleOrNull { it.type == type }?.status == PreflightStatus.PASSED
        }

    private fun rehearsalUnavailableReason(
        profiles: List<PixelCameraProfile>,
        preflight: PreflightReport,
        rehearsal: RehearsalActionUiState,
    ): String = when {
        rehearsal is RehearsalActionUiState.Running -> "A rehearsal is already running."
        rehearsal is RehearsalActionUiState.SafetyStopPending ->
            "STOP is not yet verified; wait for the independent safety alarm before retrying."
        profiles.isEmpty() -> "Install a Pixel Camera profile before running rehearsal."
        else -> rehearsalRequiredChecks.firstNotNullOfOrNull { type ->
            preflight.checks.singleOrNull { it.type == type }
                ?.takeIf { it.status != PreflightStatus.PASSED }
                ?.message
        } ?: "Screen-on rehearsal prerequisites are ready."
    }

    private fun readiness(preflight: PreflightReport): ReadinessUiState = when (
        val readiness = preflight.readiness
    ) {
        ScheduleReadiness.Ready -> ReadinessUiState.Ready(
            title = "Ready to schedule",
            summary = "All required device capabilities are currently verified.",
        )

        is ScheduleReadiness.ReadyWithWarnings -> ReadinessUiState.ReadyWithWarnings(
            title = "Ready with warnings",
            summary = "Required checks passed, but optional capabilities still need attention.",
            warnings = readiness.warnings.map(PreflightCheck::message),
        )

        is ScheduleReadiness.Blocked -> ReadinessUiState.Blocked(
            title = "Setup required",
            summary = "${readiness.blockers.size} required readiness check(s) need attention.",
        )
    }

    private fun capability(check: PreflightCheck): CapabilityUiState = CapabilityUiState(
        name = check.type.displayName,
        status = when (check.status) {
            PreflightStatus.PASSED -> CapabilityStatus.AVAILABLE
            PreflightStatus.FAILED -> CapabilityStatus.BLOCKED
            PreflightStatus.UNKNOWN -> CapabilityStatus.UNKNOWN
        },
        detail = check.message,
        required = check.severity == PreflightSeverity.BLOCKING,
        remediation = check.remediation,
    )

    private val PreflightCheckType.displayName: String
        get() = when (this) {
            PreflightCheckType.EXACT_ALARMS -> "Exact alarms"
            PreflightCheckType.NOTIFICATIONS -> "Notifications"
            PreflightCheckType.FULL_SCREEN_INTENT -> "Full-screen intent"
            PreflightCheckType.PIXEL_CAMERA_INSTALLED -> "Pixel Camera installed"
            PreflightCheckType.SECURE_CAMERA_RESOLVES -> "Secure Pixel Camera launch"
            PreflightCheckType.DEVICE_WAKE -> "Device wake"
            PreflightCheckType.ACCESSIBILITY_ENABLED -> "Lenswake Accessibility Service"
            PreflightCheckType.ACCESSIBILITY_CONNECTED -> "Accessibility runtime connection"
            PreflightCheckType.PROFILE_AVAILABLE -> "Pixel Camera profile"
            PreflightCheckType.PROFILE_COMPATIBILITY -> "Profile compatibility"
            PreflightCheckType.REHEARSAL_CURRENT -> "Physical-device rehearsal"
            PreflightCheckType.PRIVILEGED_FALLBACK -> "Privileged fallback"
            PreflightCheckType.BATTERY -> "Battery"
            PreflightCheckType.CHARGING -> "Charging"
            PreflightCheckType.STORAGE -> "Storage"
        }

    private fun scheduleSummary(schedule: RecordingSchedule): ScheduleSummaryUiState {
        val start = schedule.startAt.atZone(schedule.zoneId).format(scheduleTimeFormatter)
        val stop = schedule.stopAt.atZone(schedule.zoneId).format(scheduleTimeFormatter)
        val startLocal = schedule.startAt.atZone(schedule.zoneId).toLocalDateTime().format(editorTimeFormatter)
        val stopLocal = schedule.stopAt.atZone(schedule.zoneId).toLocalDateTime().format(editorTimeFormatter)
        return ScheduleSummaryUiState(
            id = schedule.id.value,
            title = schedule.name,
            timing = "$start - $stop",
            status = if (schedule.enabled) "Enabled" else "Disabled",
            startLocal = startLocal,
            stopLocal = stopLocal,
            zoneId = schedule.zoneId.id,
            profileId = schedule.profileId.value,
            enabled = schedule.enabled,
        )
    }

    private fun profileSummary(profile: PixelCameraProfile): ProfileSummaryUiState {
        val environment = profile.environment
        val title = if (
            environment.deviceModel.startsWith(environment.deviceManufacturer, ignoreCase = true)
        ) {
            environment.deviceModel
        } else {
            "${environment.deviceManufacturer} ${environment.deviceModel}"
        }
        return ProfileSummaryUiState(
            id = profile.id.value,
            title = title,
            environment = "Android ${environment.androidSdk} - Pixel Camera ${environment.cameraVersionCode} - ${environment.localeTag}",
            compatibility = when (profile.compatibility) {
                ProfileCompatibility.VERIFIED -> "Persisted as verified; see current compatibility in Setup"
                ProfileCompatibility.PROBABLY_COMPATIBLE -> "Probably compatible; rehearsal required"
                ProfileCompatibility.NEEDS_REHEARSAL -> "Needs rehearsal"
                ProfileCompatibility.INCOMPATIBLE -> "Incompatible"
            },
            verifiedForScheduling = profile.compatibility == ProfileCompatibility.VERIFIED && profile.verifiedAt != null,
        )
    }

    private fun eventSummary(event: AutomationEvent): DiagnosticEventUiState {
        val details = buildList {
            add(event.state.name)
            add(event.outcome.name)
            event.operation?.let { add(it.name) }
            event.failure?.let { add("${it.code.name}: ${it.message}") }
        }
        return DiagnosticEventUiState(
            id = event.id.value,
            title = event.name,
            detail = details.joinToString(" - "),
            occurredAt = event.timestamp.atOffset(ZoneOffset.UTC).format(eventTimeFormatter),
        )
    }

    private fun incidentSummary(incident: AlarmTransportIncident): AlarmTransportIncidentUiState =
        AlarmTransportIncidentUiState(
            id = incident.id,
            title = incident.title,
            detail = incident.detail,
            occurredAt = java.time.Instant.ofEpochMilli(incident.recordedAtEpochMillis)
                .atOffset(ZoneOffset.UTC)
                .format(eventTimeFormatter),
            action = when (incident.action) {
                AlarmTransportIncidentAction.OPEN_PIXEL_CAMERA -> AlarmTransportIncidentUiAction.OPEN_PIXEL_CAMERA
                null -> null
            },
        )

    private val rehearsalRequiredChecks = setOf(
        PreflightCheckType.EXACT_ALARMS,
        PreflightCheckType.PIXEL_CAMERA_INSTALLED,
        PreflightCheckType.SECURE_CAMERA_RESOLVES,
        PreflightCheckType.ACCESSIBILITY_ENABLED,
        PreflightCheckType.ACCESSIBILITY_CONNECTED,
        PreflightCheckType.PROFILE_AVAILABLE,
    )

    private val scheduleRequiredChecks = rehearsalRequiredChecks + setOf(
        PreflightCheckType.NOTIFICATIONS,
        PreflightCheckType.FULL_SCREEN_INTENT,
        PreflightCheckType.DEVICE_WAKE,
        PreflightCheckType.PROFILE_COMPATIBILITY,
        PreflightCheckType.REHEARSAL_CURRENT,
    )

    private val editorTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)

}
