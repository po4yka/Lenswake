package dev.po4yka.lenswake.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.po4yka.lenswake.R
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
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfilePersistenceIssue
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.ScheduleReadiness
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SetupRemediationAction
import dev.po4yka.lenswake.core.SystemLenswakeClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
    private val strings: UiStringProvider,
    alarmTransportIncidentSource: AlarmTransportIncidentSource = EmptyAlarmTransportIncidentSource,
    private val clock: LenswakeClock = SystemLenswakeClock(),
) : ViewModel() {
    private val preflightRefresh = MutableStateFlow(0L)
    private val profileInstall = MutableStateFlow<ProfileInstallUiState>(ProfileInstallUiState.Idle)
    private val rehearsal = MutableStateFlow<RehearsalActionUiState>(RehearsalActionUiState.Idle)
    private val scheduleEditor = MutableStateFlow<ScheduleEditorUiState>(ScheduleEditorUiState.Closed)
    private val scheduleAction = MutableStateFlow<ScheduleActionUiState>(ScheduleActionUiState.Idle)
    private val pendingDeleteScheduleId = MutableStateFlow<String?>(null)
    private val setupRemediationMessage = MutableStateFlow<String?>(null)
    private val profiles = profileRepository.observeProfiles()
    private val executions = executionRepository.observeExecutions()
    private val activeSession = executions.map { sessions ->
        sessions.asSequence()
            .filter(ExecutionSession::ownsPixelCamera)
            .sortedWith(
                compareBy<ExecutionSession> { it.expectedStopAt }
                    .thenBy { it.createdAt }
                    .thenBy { it.id.value },
            )
            .firstOrNull()
    }
    private val observedActiveSession = activeSession.flatMapLatest { session ->
        flow {
            var observedAt = clock.now()
            emit(ObservedActiveSession(session, observedAt))
            while (session != null && session.expectedStopAt.isAfter(observedAt)) {
                val remaining = Duration.between(observedAt, session.expectedStopAt)
                val floorMillis = remaining.toMillis()
                val ceilingMillis = if (remaining.minusMillis(floorMillis).isZero) {
                    floorMillis
                } else {
                    floorMillis + 1L
                }
                delay(ceilingMillis.coerceIn(1L, DEADLINE_RECHECK_MILLIS))
                observedAt = clock.now()
            }
            if (session != null) {
                emit(ObservedActiveSession(session, observedAt))
            }
        }
    }
    private val profilePersistenceIssues = profileRepository.observePersistenceIssues()
    private val preflightInvalidations = merge(
        preflightRefresh.map { Unit },
        runtimePreflightProbe.invalidations,
    )
    private val diagnosticEvents = executions
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
        combine(
            diagnosticEvents,
            alarmTransportIncidents,
            profilePersistenceIssues,
            observedActiveSession,
            ::DiagnosticsUiData,
        ),
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
            profileIssues = diagnostics.profileIssues,
            activeSession = diagnostics.activeSession.session,
            now = diagnostics.activeSession.observedAt,
            preflight = preflight,
            profileInstall = transientState.profileInstall,
            rehearsal = transientState.rehearsal,
            scheduleEditor = transientState.schedule.editor,
            scheduleAction = transientState.schedule.action,
            pendingDeleteScheduleId = transientState.schedule.pendingDeleteScheduleId,
            setupRemediationMessage = transientState.schedule.remediationMessage,
            strings = strings,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LenswakeUiStateMapper.initial(strings),
    )

    fun refreshPreflight() {
        preflightRefresh.value += 1
    }

    fun reportSetupRemediationUnavailable(action: SetupRemediationAction) {
        setupRemediationMessage.value = strings.get(
            R.string.remediation_unavailable,
            action.remediationLabel(strings),
        )
        refreshPreflight()
    }

    fun clearSetupRemediationMessage() {
        setupRemediationMessage.value = null
    }

    fun installCandidateProfile() {
        if (profileInstall.value == ProfileInstallUiState.Installing) return

        profileInstall.value = ProfileInstallUiState.Installing
        viewModelScope.launch {
            profileInstall.value = installKnownPixelCameraProfile().toUiState(strings)
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
                    RehearsalActionUiState.Failed(strings.get(R.string.rehearsal_profile_required))
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
                    ).toUiState(strings)
                }
                refreshPreflight()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                rehearsal.value = RehearsalActionUiState.Failed(
                    strings.get(R.string.rehearsal_unexpected_failure),
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
                strings.get(R.string.action_create_profile_required),
            )
            return
        }
        scheduleAction.value = ScheduleActionUiState.Idle
        val zoneId = ZoneId.systemDefault()
        val startLocal = defaultScheduleStart(clock.now(), zoneId)
        scheduleEditor.value = ScheduleEditorUiState.Open(
            mode = ScheduleEditorMode.Create,
            form = ScheduleFormUiState(
                name = strings.get(R.string.default_schedule_name),
                startLocal = startLocal,
                stopLocal = startLocal.plusHours(DEFAULT_RECORDING_DURATION_HOURS),
                profileId = profile.id,
                zoneId = zoneId,
            ),
        )
    }

    fun beginEditSchedule(scheduleId: String) {
        val schedule = state.value.schedules.firstOrNull { it.id == scheduleId }
        if (schedule == null) {
            scheduleAction.value = ScheduleActionUiState.Failed(
                strings.get(R.string.schedule_selected_missing),
            )
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
                error = strings.get(R.string.validation_schedule_form_invalid),
            )
            return
        }
        launchScheduleMutation(strings.get(R.string.schedule_saving)) {
            when (val mode = editor.mode) {
                ScheduleEditorMode.Create -> scheduleWorkflow.create(command)
                is ScheduleEditorMode.Edit -> scheduleWorkflow.edit(ScheduleId(mode.scheduleId), command)
            }
        }
    }

    fun setScheduleEnabled(scheduleId: String, enabled: Boolean) {
        launchScheduleMutation(
            strings.get(if (enabled) R.string.schedule_enabling else R.string.schedule_disabling),
        ) {
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
        if (scheduleAction.value is ScheduleActionUiState.Working) return
        pendingDeleteScheduleId.value = null
        launchScheduleMutation(strings.get(R.string.schedule_deleting)) {
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
                scheduleAction.value = result.toUiState(strings)
                if (result is ScheduleWorkflowResult.Applied || result is ScheduleWorkflowResult.Deleted) {
                    scheduleEditor.value = ScheduleEditorUiState.Closed
                    pendingDeleteScheduleId.value = null
                    refreshPreflight()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                scheduleAction.value = ScheduleActionUiState.Failed(
                    strings.get(R.string.schedule_change_failed),
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
        private val strings: UiStringProvider,
        private val alarmTransportIncidentSource: AlarmTransportIncidentSource = EmptyAlarmTransportIncidentSource,
        private val clock: LenswakeClock = SystemLenswakeClock(),
    ) : ViewModelProvider.Factory {
        constructor(
            graph: ApplicationGraph,
            strings: UiStringProvider,
        ) : this(
            scheduleRepository = graph.scheduleRepository,
            profileRepository = graph.profileRepository,
            executionRepository = graph.executionRepository,
            alarmTransportIncidentSource = graph.alarmTransportIncidentSource,
            runtimePreflightProbe = graph.runtimePreflightProbe,
            installKnownPixelCameraProfile = graph.installKnownPixelCameraProfile,
            rehearsalCoordinator = graph.rehearsalCoordinator,
            scheduleWorkflow = graph.scheduleWorkflow,
            strings = strings,
            clock = graph.clock,
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
                strings = strings,
                clock = clock,
            ) as T
        }
    }

    private companion object {
        const val MAX_OBSERVED_SESSIONS = 10
        const val MAX_VISIBLE_EVENTS = 50
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val REHEARSAL_DURATION_SECONDS = 10L
        const val DEFAULT_RECORDING_DURATION_HOURS = 1L
        const val DEADLINE_RECHECK_MILLIS = 30_000L
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
    val profileIssues: List<ProfilePersistenceIssue>,
    val activeSession: ObservedActiveSession,
)

private data class ObservedActiveSession(
    val session: ExecutionSession?,
    val observedAt: java.time.Instant,
)

private fun SetupRemediationAction.remediationLabel(strings: UiStringProvider): String = strings.get(
    when (this) {
        SetupRemediationAction.REQUEST_NOTIFICATION_PERMISSION -> R.string.remediation_notification_permission
        SetupRemediationAction.REQUEST_MEDIA_VIDEO_PERMISSION -> R.string.remediation_media_video_permission
        SetupRemediationAction.OPEN_NOTIFICATION_SETTINGS -> R.string.remediation_notification_settings
        SetupRemediationAction.OPEN_EXACT_ALARM_SETTINGS -> R.string.remediation_exact_alarm_settings
        SetupRemediationAction.OPEN_ACCESSIBILITY_SETTINGS -> R.string.remediation_accessibility_settings
        SetupRemediationAction.OPEN_FULL_SCREEN_INTENT_SETTINGS ->
            R.string.remediation_full_screen_intent_settings
    },
)

private fun ScheduleFormUiState.toCommandOrNull(): ScheduleCommand? = runCatching {
    val start = requireNotNull(startLocal)
    val stop = requireNotNull(stopLocal)
    require(name.isNotBlank())
    require(profileId.isNotBlank())
    ScheduleCommand(
        name = name.trim(),
        startAt = start.toUnambiguousInstant(zoneId),
        stopAt = stop.toUnambiguousInstant(zoneId),
        zoneId = zoneId,
        profileId = dev.po4yka.lenswake.core.ProfileId(profileId.trim()),
        enabled = enabled,
    )
}.getOrNull()

private fun LocalDateTime.toUnambiguousInstant(zoneId: ZoneId): java.time.Instant {
    val offsets = zoneId.rules.getValidOffsets(this)
    require(offsets.size == 1) { "Local time must exist exactly once in its time zone" }
    return toInstant(offsets.single())
}

private fun ScheduleWorkflowResult.toUiState(strings: UiStringProvider): ScheduleActionUiState = when (this) {
    is ScheduleWorkflowResult.Applied -> ScheduleActionUiState.Succeeded(
        when (operation) {
            ScheduleOperation.CREATED -> if (schedule.enabled) {
                strings.get(R.string.schedule_created)
            } else {
                strings.get(R.string.schedule_draft_created)
            }
            ScheduleOperation.UPDATED -> if (schedule.enabled) {
                strings.get(R.string.schedule_updated)
            } else {
                strings.get(R.string.schedule_draft_updated)
            }
            ScheduleOperation.ENABLED -> strings.get(R.string.schedule_enabled)
            ScheduleOperation.DISABLED -> strings.get(R.string.schedule_disabled)
        },
    )

    is ScheduleWorkflowResult.Deleted -> ScheduleActionUiState.Succeeded(
        strings.get(R.string.schedule_deleted),
    )

    is ScheduleWorkflowResult.Rejected -> ScheduleActionUiState.Failed(
        message = message,
    )

    is ScheduleWorkflowResult.Failed -> ScheduleActionUiState.Failed(
        message = message,
        rollbackFailures = rollbackFailures,
    )
}

private fun RehearsalResult.toUiState(strings: UiStringProvider): RehearsalActionUiState = when (this) {
    is RehearsalResult.Completed -> RehearsalActionUiState.Passed(
        strings.get(R.string.rehearsal_passed),
    )
    is RehearsalResult.Busy -> RehearsalActionUiState.Failed(
        strings.get(R.string.rehearsal_busy),
    )
    is RehearsalResult.Rejected -> RehearsalActionUiState.Failed(
        message,
    )
    is RehearsalResult.SafetyStopPending -> RehearsalActionUiState.SafetyStopPending(
        sessionId = sessionId.value,
        message = strings.get(R.string.rehearsal_safety_stop_pending, message),
    )
}

private fun InstallKnownPixelCameraProfileResult.toUiState(
    strings: UiStringProvider,
): ProfileInstallUiState = when (this) {
    is InstallKnownPixelCameraProfileResult.Installed -> ProfileInstallUiState.Succeeded(
        message = if (replacedExisting) {
            strings.get(R.string.profile_updated)
        } else {
            strings.get(R.string.profile_installed)
        },
    )

    is InstallKnownPixelCameraProfileResult.AlreadyInstalled -> ProfileInstallUiState.Succeeded(
        message = strings.get(R.string.profile_already_installed),
    )

    is InstallKnownPixelCameraProfileResult.UnsupportedEnvironment -> ProfileInstallUiState.Failed(
        message = strings.get(R.string.profile_unsupported_environment, environment.deviceModel),
    )

    is InstallKnownPixelCameraProfileResult.EnvironmentUnavailable -> ProfileInstallUiState.Failed(
        message = strings.get(R.string.profile_environment_unavailable, failure.message),
    )

    is InstallKnownPixelCameraProfileResult.PersistenceFailure -> ProfileInstallUiState.Failed(
        message = strings.get(R.string.profile_persistence_failure, detail),
    )
}

internal object LenswakeUiStateMapper {
    private val eventTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private const val MAX_VISIBLE_PERSISTENCE_KEY_LENGTH = 64

    fun initial(strings: UiStringProvider): LenswakeUiState = LenswakeUiState(
        readiness = ReadinessUiState.Blocked(
            title = strings.get(R.string.readiness_setup_required_title),
            summary = strings.get(R.string.readiness_setup_required_summary),
        ),
        capabilities = initialCapabilities(strings),
        actions = UiActionAvailability(
            createScheduleUnavailableReason = strings.get(R.string.action_create_unavailable_default),
            installCandidateProfileUnavailableReason = strings.get(R.string.action_profile_unchecked_default),
            rehearsalUnavailableReason = strings.get(R.string.action_rehearsal_unavailable_default),
            exportDiagnosticsUnavailableReason = strings.get(R.string.action_diagnostics_empty_default),
        ),
    )

    fun map(
        schedules: List<RecordingSchedule>,
        profiles: List<PixelCameraProfile>,
        events: List<AutomationEvent>,
        incidents: List<AlarmTransportIncident> = emptyList(),
        profileIssues: List<ProfilePersistenceIssue> = emptyList(),
        preflight: PreflightReport,
        profileInstall: ProfileInstallUiState = ProfileInstallUiState.Idle,
        rehearsal: RehearsalActionUiState = RehearsalActionUiState.Idle,
        activeSession: ExecutionSession? = null,
        now: java.time.Instant,
        scheduleEditor: ScheduleEditorUiState = ScheduleEditorUiState.Closed,
        scheduleAction: ScheduleActionUiState = ScheduleActionUiState.Idle,
        pendingDeleteScheduleId: String? = null,
        setupRemediationMessage: String? = null,
        strings: UiStringProvider,
    ): LenswakeUiState = LenswakeUiState(
        readiness = readiness(preflight, strings),
        schedules = schedules
            .sortedBy { it.startAt }
            .map { scheduleSummary(it, strings) },
        profiles = profiles
            .sortedWith(compareBy({ it.environment.deviceModel }, { it.id.value }))
            .map { profileSummary(it, strings) },
        capabilities = preflight.checks.map { capability(it, strings) },
        diagnosticEvents = events.map(::eventSummary),
        alarmTransportIncidents = incidents.map(::incidentSummary),
        profilePersistenceIssues = profileIssues.map { profilePersistenceIssueSummary(it, strings) },
        profileInstall = profileInstall,
        rehearsal = rehearsal.takeUnless {
            it is RehearsalActionUiState.SafetyStopPending &&
                activeSession?.id?.value != it.sessionId
        } ?: RehearsalActionUiState.Idle,
        activeSession = activeSession?.toActiveSessionUiState(now, strings),
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
                    strings.get(R.string.action_create_setup_required)
                profiles.none { it.compatibility == ProfileCompatibility.VERIFIED && it.verifiedAt != null } ->
                    strings.get(R.string.action_create_profile_required)
                scheduleAction is ScheduleActionUiState.Working -> strings.get(R.string.action_create_busy)
                else -> strings.get(R.string.action_create_available)
            },
            canInstallCandidateProfile = profileInstall !is ProfileInstallUiState.Installing &&
                profileInstall !is ProfileInstallUiState.Succeeded,
            installCandidateProfileUnavailableReason = when {
                profileInstall is ProfileInstallUiState.Installing -> strings.get(R.string.action_profile_installing)
                profileInstall is ProfileInstallUiState.Succeeded -> strings.get(R.string.action_profile_installed)
                profiles.isEmpty() -> strings.get(R.string.action_profile_retry)
                else -> strings.get(R.string.action_profile_update_check)
            },
            canRunRehearsal = canRunRehearsal(profiles, preflight, rehearsal, activeSession),
            rehearsalUnavailableReason = rehearsalUnavailableReason(
                profiles,
                preflight,
                rehearsal,
                activeSession,
                strings,
            ),
            canExportDiagnostics = false,
            exportDiagnosticsUnavailableReason = if (events.isEmpty()) {
                strings.get(R.string.action_diagnostics_empty_default)
            } else {
                strings.get(R.string.action_diagnostics_not_implemented)
            },
        ),
    )

    private fun canRunRehearsal(
        profiles: List<PixelCameraProfile>,
        preflight: PreflightReport,
        rehearsal: RehearsalActionUiState,
        activeSession: ExecutionSession?,
    ): Boolean = profiles.isNotEmpty() &&
        activeSession == null &&
        rehearsal !is RehearsalActionUiState.Running &&
        rehearsalRequiredChecks.all { type ->
            preflight.checks.singleOrNull { it.type == type }?.status == PreflightStatus.PASSED
        }

    private fun PreflightReport.hasAllScheduleChecksPassed(): Boolean =
        scheduleRequiredChecks.all { type ->
            checks.singleOrNull { it.type == type }?.let { check ->
                check.severity != dev.po4yka.lenswake.core.PreflightSeverity.BLOCKING ||
                    check.status == PreflightStatus.PASSED
            } == true
        }

    private fun rehearsalUnavailableReason(
        profiles: List<PixelCameraProfile>,
        preflight: PreflightReport,
        rehearsal: RehearsalActionUiState,
        activeSession: ExecutionSession?,
        strings: UiStringProvider,
    ): String = when {
        activeSession != null -> strings.get(
            R.string.action_rehearsal_active_session,
            activeSession.id.value,
            formatStopDeadline(activeSession),
        )
        rehearsal is RehearsalActionUiState.Running -> strings.get(R.string.action_rehearsal_running)
        profiles.isEmpty() -> strings.get(R.string.action_rehearsal_profile_required)
        else -> rehearsalRequiredChecks.firstNotNullOfOrNull { type ->
            preflight.checks.singleOrNull { it.type == type }
                ?.takeIf { it.status != PreflightStatus.PASSED }
                ?.message
        } ?: strings.get(R.string.action_rehearsal_ready)
    }

    private fun ExecutionSession.toActiveSessionUiState(
        now: java.time.Instant,
        strings: UiStringProvider,
    ): ActiveSessionUiState = ActiveSessionUiState(
        sessionId = id.value,
        kind = when (kind) {
            SessionKind.SCHEDULED -> ActiveSessionKind.SCHEDULED
            SessionKind.REHEARSAL -> ActiveSessionKind.REHEARSAL
        },
        stopDeadline = expectedStopAt,
        title = when (kind) {
            SessionKind.SCHEDULED -> scheduleName?.takeIf(String::isNotBlank)?.let { name ->
                strings.get(R.string.schedules_active_session_named_title, name)
            } ?: strings.get(R.string.schedules_active_session_title)
            SessionKind.REHEARSAL -> strings.get(R.string.profiles_active_rehearsal_title)
        },
        detail = strings.get(
            R.string.active_session_detail,
            id.value,
            formatStopDeadline(this),
        ),
        status = when {
            !expectedStopAt.isAfter(now) -> strings.get(R.string.status_stop_overdue)
            status == dev.po4yka.lenswake.core.SessionStatus.STOPPING ||
                status == dev.po4yka.lenswake.core.SessionStatus.FAILED ||
                stopActionAt != null ||
                alarmStopDeliveredAt != null -> strings.get(R.string.status_stop_pending)
            status == dev.po4yka.lenswake.core.SessionStatus.PENDING ||
                status == dev.po4yka.lenswake.core.SessionStatus.STARTING ->
                strings.get(R.string.status_preparing)
            else -> strings.get(R.string.status_recording_expected)
        },
    )

    private fun formatStopDeadline(session: ExecutionSession): String =
        session.expectedStopAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)

    private fun readiness(
        preflight: PreflightReport,
        strings: UiStringProvider,
    ): ReadinessUiState = when (
        val readiness = preflight.readiness
    ) {
        ScheduleReadiness.Ready -> ReadinessUiState.Ready(
            title = strings.get(R.string.readiness_ready_title),
            summary = strings.get(R.string.readiness_ready_summary),
        )

        is ScheduleReadiness.ReadyWithWarnings -> ReadinessUiState.ReadyWithWarnings(
            title = strings.get(R.string.status_ready_with_warnings),
            summary = strings.get(R.string.readiness_warnings_summary),
            warnings = readiness.warnings.map(PreflightCheck::message),
        )

        is ScheduleReadiness.Blocked -> ReadinessUiState.Blocked(
            title = strings.get(R.string.readiness_setup_required_title),
            summary = strings.quantity(
                R.plurals.readiness_blocker_count,
                readiness.blockers.size,
                readiness.blockers.size,
            ),
        )
    }

    private fun capability(
        check: PreflightCheck,
        strings: UiStringProvider,
    ): CapabilityUiState = CapabilityUiState(
        name = check.type.displayName(strings),
        status = when (check.status) {
            PreflightStatus.PASSED -> CapabilityStatus.AVAILABLE
            PreflightStatus.FAILED -> CapabilityStatus.BLOCKED
            PreflightStatus.UNKNOWN -> CapabilityStatus.UNKNOWN
        },
        detail = check.message,
        required = check.severity == PreflightSeverity.BLOCKING,
        remediation = check.remediation,
    )

    private fun PreflightCheckType.displayName(strings: UiStringProvider): String = strings.get(
        when (this) {
            PreflightCheckType.EXACT_ALARMS -> R.string.capability_exact_alarms
            PreflightCheckType.NOTIFICATIONS -> R.string.capability_notifications
            PreflightCheckType.MEDIA_VIDEO_ACCESS -> R.string.capability_media_video_access
            PreflightCheckType.FULL_SCREEN_INTENT -> R.string.capability_full_screen_intent
            PreflightCheckType.PIXEL_CAMERA_INSTALLED -> R.string.capability_pixel_camera_installed
            PreflightCheckType.SECURE_CAMERA_RESOLVES -> R.string.capability_secure_camera_launch
            PreflightCheckType.DEVICE_WAKE -> R.string.capability_device_wake
            PreflightCheckType.ACCESSIBILITY_ENABLED -> R.string.capability_accessibility_service
            PreflightCheckType.ACCESSIBILITY_CONNECTED -> R.string.capability_accessibility_connection
            PreflightCheckType.PROFILE_AVAILABLE -> R.string.capability_camera_profile
            PreflightCheckType.PROFILE_COMPATIBILITY -> R.string.capability_profile_compatibility
            PreflightCheckType.REHEARSAL_CURRENT -> R.string.capability_camera_test
            PreflightCheckType.PRIVILEGED_FALLBACK -> R.string.capability_privileged_fallback
            PreflightCheckType.BATTERY -> R.string.capability_battery
            PreflightCheckType.CHARGING -> R.string.capability_charging
            PreflightCheckType.STORAGE -> R.string.capability_storage
        },
    )

    private fun scheduleSummary(
        schedule: RecordingSchedule,
        strings: UiStringProvider,
    ): ScheduleSummaryUiState {
        val scheduleTimeFormatter = DateTimeFormatter
            .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
        val start = schedule.startAt.atZone(schedule.zoneId).format(scheduleTimeFormatter)
        val stop = schedule.stopAt.atZone(schedule.zoneId).format(scheduleTimeFormatter)
        val startLocal = schedule.startAt.atZone(schedule.zoneId).toLocalDateTime()
        val stopLocal = schedule.stopAt.atZone(schedule.zoneId).toLocalDateTime()
        return ScheduleSummaryUiState(
            id = schedule.id.value,
            title = schedule.name,
            timing = strings.get(R.string.schedule_time_range, start, stop),
            status = strings.get(if (schedule.enabled) R.string.status_enabled else R.string.status_disabled),
            startLocal = startLocal,
            stopLocal = stopLocal,
            zoneId = schedule.zoneId,
            profileId = schedule.profileId.value,
            enabled = schedule.enabled,
        )
    }

    private fun profileSummary(
        profile: PixelCameraProfile,
        strings: UiStringProvider,
    ): ProfileSummaryUiState {
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
            environment = strings.get(
                R.string.profile_environment_summary,
                environment.androidSdk,
                environment.cameraVersionCode,
                Locale.forLanguageTag(environment.localeTag).getDisplayName(Locale.getDefault()),
            ),
            compatibility = when (profile.compatibility) {
                ProfileCompatibility.VERIFIED -> strings.get(R.string.profile_compatibility_verified)
                ProfileCompatibility.PROBABLY_COMPATIBLE -> strings.get(R.string.profile_compatibility_likely)
                ProfileCompatibility.NEEDS_REHEARSAL -> strings.get(R.string.profile_compatibility_needs_test)
                ProfileCompatibility.INCOMPATIBLE -> strings.get(R.string.profile_compatibility_incompatible)
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

    private fun profilePersistenceIssueSummary(
        issue: ProfilePersistenceIssue,
        strings: UiStringProvider,
    ): ProfilePersistenceIssueUiState {
        val displayKey = issue.entryKey
            .take(MAX_VISIBLE_PERSISTENCE_KEY_LENGTH)
            .map { character -> if (character.isISOControl()) '\uFFFD' else character }
            .joinToString("")
            .ifBlank { strings.get(R.string.profile_storage_issue_blank_key) }
        return ProfilePersistenceIssueUiState(
            id = issue.entryKey,
            title = strings.get(R.string.profile_storage_issue_title),
            detail = strings.get(R.string.profile_storage_issue_detail, displayKey),
        )
    }

    private fun initialCapabilities(strings: UiStringProvider): List<CapabilityUiState> = listOf(
        CapabilityUiState(
            name = strings.get(R.string.capability_exact_alarms),
            status = CapabilityStatus.UNKNOWN,
            detail = strings.get(R.string.capability_exact_alarms_unchecked),
            required = true,
        ),
        CapabilityUiState(
            name = strings.get(R.string.capability_device_wake),
            status = CapabilityStatus.BLOCKED,
            detail = strings.get(R.string.capability_device_wake_unavailable),
            required = true,
        ),
        CapabilityUiState(
            name = strings.get(R.string.capability_accessibility_service),
            status = CapabilityStatus.BLOCKED,
            detail = strings.get(R.string.capability_accessibility_unchecked),
            required = true,
        ),
        CapabilityUiState(
            name = strings.get(R.string.capability_camera_profile),
            status = CapabilityStatus.BLOCKED,
            detail = strings.get(R.string.capability_profile_unavailable),
            required = true,
        ),
        CapabilityUiState(
            name = strings.get(R.string.capability_camera_test),
            status = CapabilityStatus.BLOCKED,
            detail = strings.get(R.string.capability_test_required),
            required = true,
        ),
        CapabilityUiState(
            name = strings.get(R.string.capability_privileged_fallback),
            status = CapabilityStatus.UNKNOWN,
            detail = strings.get(R.string.capability_privileged_unchecked),
            required = false,
        ),
    )

    private val rehearsalRequiredChecks = setOf(
        PreflightCheckType.EXACT_ALARMS,
        PreflightCheckType.MEDIA_VIDEO_ACCESS,
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
        PreflightCheckType.BATTERY,
        PreflightCheckType.CHARGING,
        PreflightCheckType.STORAGE,
    )

}
