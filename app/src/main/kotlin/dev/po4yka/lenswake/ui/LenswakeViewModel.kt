package dev.po4yka.lenswake.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfile
import dev.po4yka.lenswake.application.InstallReleaseCertification
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfileResult
import dev.po4yka.lenswake.application.AlarmTransportIncident
import dev.po4yka.lenswake.application.AlarmTransportIncidentAction
import dev.po4yka.lenswake.application.AlarmTransportIncidentSource
import dev.po4yka.lenswake.application.EmptyAlarmTransportIncidentSource
import dev.po4yka.lenswake.application.RehearsalCoordinator
import dev.po4yka.lenswake.application.RehearsalResult
import dev.po4yka.lenswake.application.RehearsalResultCode
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.application.ScheduleCommand
import dev.po4yka.lenswake.application.ScheduleOperation
import dev.po4yka.lenswake.application.ScheduleWorkflow
import dev.po4yka.lenswake.application.ScheduleWorkflowFailureCode
import dev.po4yka.lenswake.application.ScheduleWorkflowResult
import dev.po4yka.lenswake.alarm.AlarmTransportFailureCode
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
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
import dev.po4yka.lenswake.core.supportedCaptureConfigurations
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
    private val installReleaseCertification: InstallReleaseCertification? = null,
    alarmTransportIncidentSource: AlarmTransportIncidentSource = EmptyAlarmTransportIncidentSource,
    private val clock: LenswakeClock = SystemLenswakeClock(),
    private val actionState: LenswakeViewModelActionState = LenswakeViewModelActionState(),
    profileActions: LenswakeProfileActions = LenswakeProfileActionsImpl(
        actionState = actionState,
        profileRepository = profileRepository,
        scheduleRepository = scheduleRepository,
        executions = executionRepository,
        installKnownPixelCameraProfile = installKnownPixelCameraProfile,
        installReleaseCertification = installReleaseCertification,
        rehearsalCoordinator = rehearsalCoordinator,
        strings = strings,
    ),
    scheduleActions: LenswakeScheduleActions = LenswakeScheduleActionsImpl(
        actionState = actionState,
        scheduleWorkflow = scheduleWorkflow,
        strings = strings,
        clock = clock,
    ),
) : ViewModel(),
    LenswakeProfileActions by profileActions,
    LenswakeScheduleActions by scheduleActions {
    private val preflightRefresh = actionState.preflightRefresh
    private val profileInstall = actionState.profileInstall
    private val rehearsal = actionState.rehearsal
    private val scheduleEditor = actionState.scheduleEditor
    private val scheduleAction = actionState.scheduleAction
    private val pendingDeleteScheduleId = actionState.pendingDeleteScheduleId
    private val setupRemediationMessage = actionState.setupRemediationMessage
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
                .sortedWith(DIAGNOSTIC_SESSION_ORDER)
                .take(MAX_OBSERVED_SESSIONS)
                .map { executionRepository.observeEvents(it.id) }

            if (eventFlows.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(eventFlows) { eventsBySession ->
                    eventsBySession
                        .flatMap { it }
                }
            }
        }
    private val alarmTransportIncidents = alarmTransportIncidentSource.incidents
    private val transientUiState = combine(
        profileInstall,
        rehearsal,
        combine(
            scheduleEditor,
            scheduleAction,
            pendingDeleteScheduleId,
            setupRemediationMessage,
        ) { editor, action, pendingDelete, remediationMessage ->
            ScheduleTransientUiState(editor, action, pendingDelete, remediationMessage)
        },
    ) { install, rehearsalSnapshot, scheduleTransient ->
        TransientUiState(
            profileInstall = install,
            rehearsal = rehearsalSnapshot.action,
            rehearsalTarget = rehearsalSnapshot.target,
            schedule = scheduleTransient,
        )
    }

    val state: StateFlow<LenswakeUiState> = combine(
        scheduleRepository.observeSchedules(),
        profiles,
        combine(
            diagnosticEvents,
            alarmTransportIncidents,
            profilePersistenceIssues,
            observedActiveSession,
            executions,
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
            executions = diagnostics.executions,
            activeSession = diagnostics.activeSession.session,
            now = diagnostics.activeSession.observedAt,
            preflight = preflight,
            profileInstall = transientState.profileInstall,
            rehearsal = transientState.rehearsal,
            rehearsalTarget = transientState.rehearsalTarget,
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

    init {
        actionState.bind(state, viewModelScope)
    }

    fun refreshPreflight() {
        actionState.refreshPreflight()
    }

    fun diagnosticsExport(): String? = DiagnosticsExportFormatter.format(state.value, strings)

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

    class Factory internal constructor(
        private val scheduleRepository: ScheduleRepository,
        private val profileRepository: AutomationProfileRepository,
        private val executionRepository: ExecutionRepository,
        private val runtimePreflightProbe: RuntimePreflightProbe,
        private val installKnownPixelCameraProfile: InstallKnownPixelCameraProfile,
        private val rehearsalCoordinator: RehearsalCoordinator,
        private val scheduleWorkflow: ScheduleWorkflow,
        private val strings: UiStringProvider,
        private val installReleaseCertification: InstallReleaseCertification? = null,
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
            installReleaseCertification = graph.installReleaseCertification,
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
                installReleaseCertification = installReleaseCertification,
                rehearsalCoordinator = rehearsalCoordinator,
                scheduleWorkflow = scheduleWorkflow,
                strings = strings,
                clock = clock,
            ) as T
        }
    }

    private companion object {
        const val MAX_OBSERVED_SESSIONS = 10
        const val STOP_TIMEOUT_MILLIS = 5_000L
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
    val rehearsalTarget: RehearsalTargetUiState?,
    val schedule: ScheduleTransientUiState,
)

private data class DiagnosticsUiData(
    val events: List<AutomationEvent>,
    val incidents: List<AlarmTransportIncident>,
    val profileIssues: List<ProfilePersistenceIssue>,
    val activeSession: ObservedActiveSession,
    val executions: List<ExecutionSession>,
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

internal fun RehearsalResult.toUiState(strings: UiStringProvider): RehearsalActionUiState = when (this) {
    is RehearsalResult.Completed -> RehearsalActionUiState.Passed(
        strings.get(R.string.rehearsal_passed),
    )
    is RehearsalResult.Busy -> RehearsalActionUiState.Failed(
        strings.get(R.string.rehearsal_busy),
    )
    is RehearsalResult.Rejected -> RehearsalActionUiState.Failed(
        strings.get(code.messageResource()),
    )
    is RehearsalResult.SafetyStopPending -> RehearsalActionUiState.SafetyStopPending(
        sessionId = sessionId.value,
        message = strings.get(R.string.rehearsal_safety_stop_pending_generic),
    )
}

internal fun InstallKnownPixelCameraProfileResult.toUiState(
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

    is InstallKnownPixelCameraProfileResult.ExperimentalConsentRequired ->
        ProfileInstallUiState.ExperimentalConsentRequired(
            message = strings.get(R.string.profile_experimental_consent),
        )

    is InstallKnownPixelCameraProfileResult.EnvironmentUnavailable -> ProfileInstallUiState.Failed(
        message = strings.get(R.string.profile_environment_unavailable),
    )

    is InstallKnownPixelCameraProfileResult.PersistenceFailure -> ProfileInstallUiState.Failed(
        message = strings.get(R.string.profile_persistence_failure),
    )
}

private fun RehearsalResultCode.messageResource(): Int = when (this) {
    RehearsalResultCode.PROFILE_NOT_FOUND -> R.string.rehearsal_error_profile_not_found
    RehearsalResultCode.ENVIRONMENT_UNAVAILABLE -> R.string.rehearsal_error_environment_unavailable
    RehearsalResultCode.ENVIRONMENT_MISMATCH -> R.string.rehearsal_error_environment_mismatch
    RehearsalResultCode.ACTIVE_REHEARSAL_EXISTS -> R.string.rehearsal_error_active
    RehearsalResultCode.SESSION_PERSISTENCE_FAILED -> R.string.rehearsal_error_session_persistence
    RehearsalResultCode.SNAPSHOT_CAPTURE_FAILED -> R.string.rehearsal_error_snapshot
    RehearsalResultCode.BACKSTOP_UNAVAILABLE -> R.string.rehearsal_error_backstop
    RehearsalResultCode.START_FAILED -> R.string.rehearsal_error_start
    RehearsalResultCode.STOP_FAILED -> R.string.rehearsal_error_stop
    RehearsalResultCode.PROMOTION_PROOF_MISSING -> R.string.rehearsal_error_proof_missing
    RehearsalResultCode.PROFILE_PROMOTION_FAILED -> R.string.rehearsal_error_promotion
    RehearsalResultCode.INVALID_STOP_TRIGGER -> R.string.rehearsal_error_invalid_stop
}

internal object LenswakeUiStateMapper {
    fun initial(strings: UiStringProvider): LenswakeUiState = LenswakeUiState(
        readiness = ReadinessUiState.Blocked(
            title = strings.get(R.string.readiness_setup_required_title),
            summary = strings.get(R.string.readiness_setup_required_summary),
        ),
        capabilities = LenswakeReadinessUiMapper.initialCapabilities(strings),
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
        executions: List<ExecutionSession> = emptyList(),
        preflight: PreflightReport,
        profileInstall: ProfileInstallUiState = ProfileInstallUiState.Idle,
        rehearsal: RehearsalActionUiState = RehearsalActionUiState.Idle,
        rehearsalTarget: RehearsalTargetUiState? = null,
        activeSession: ExecutionSession? = null,
        now: java.time.Instant,
        scheduleEditor: ScheduleEditorUiState = ScheduleEditorUiState.Closed,
        scheduleAction: ScheduleActionUiState = ScheduleActionUiState.Idle,
        pendingDeleteScheduleId: String? = null,
        setupRemediationMessage: String? = null,
        strings: UiStringProvider,
    ): LenswakeUiState = LenswakeUiState(
        readiness = LenswakeReadinessUiMapper.readiness(preflight, strings),
        schedules = schedules
            .sortedBy { it.startAt }
            .map { LenswakeScheduleUiMapper.summary(it, strings) },
        profiles = profiles
            .sortedWith(compareBy({ it.environment.deviceModel }, { it.id.value }))
            .map { LenswakeProfileUiMapper.summary(it, executions, strings) },
        capabilities = preflight.checks.map { LenswakeReadinessUiMapper.capability(it, strings) },
        diagnosticSessions = LenswakeDiagnosticsUiMapper.sessions(executions, events, strings),
        alarmTransportIncidents = incidents.map { LenswakeDiagnosticsUiMapper.incidentSummary(it, strings) },
        profilePersistenceIssues = profileIssues.map {
            LenswakeDiagnosticsUiMapper.profilePersistenceIssueSummary(it, strings)
        },
        profileInstall = profileInstall,
        rehearsal = rehearsal.takeUnless {
            it is RehearsalActionUiState.SafetyStopPending &&
                activeSession?.id?.value != it.sessionId
        } ?: RehearsalActionUiState.Idle,
        rehearsalTarget = rehearsalTarget ?: activeSession
            ?.takeIf { it.kind == SessionKind.REHEARSAL }
            ?.let { session ->
                session.scheduleId?.let { RehearsalTargetUiState.Schedule(it.value) }
                    ?: RehearsalTargetUiState.Profile(session.profileId.value)
            },
        activeSession = activeSession?.let { LenswakeActiveSessionUiMapper.map(it, now, strings) },
        scheduleEditor = scheduleEditor,
        scheduleAction = scheduleAction,
        pendingDeleteScheduleId = pendingDeleteScheduleId,
        setupRemediationMessage = setupRemediationMessage,
        actions = LenswakeActionAvailabilityMapper.map(
            profiles = profiles,
            executions = executions,
            events = events,
            incidents = incidents,
            profileIssues = profileIssues,
            preflight = preflight,
            profileInstall = profileInstall,
            rehearsal = rehearsal,
            activeSession = activeSession,
            scheduleAction = scheduleAction,
            strings = strings,
        ),
    )

}
