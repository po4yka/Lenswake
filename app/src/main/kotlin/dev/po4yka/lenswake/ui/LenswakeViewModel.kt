package dev.po4yka.lenswake.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfile
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfileResult
import dev.po4yka.lenswake.application.RehearsalCoordinator
import dev.po4yka.lenswake.application.RehearsalResult
import dev.po4yka.lenswake.application.RuntimePreflightProbe
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
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.di.ApplicationGraph
import java.time.Duration
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
class LenswakeViewModel(
    scheduleRepository: ScheduleRepository,
    private val profileRepository: AutomationProfileRepository,
    executionRepository: ExecutionRepository,
    private val runtimePreflightProbe: RuntimePreflightProbe,
    private val installKnownPixelCameraProfile: InstallKnownPixelCameraProfile,
    private val rehearsalCoordinator: RehearsalCoordinator,
) : ViewModel() {
    private val preflightRefresh = MutableStateFlow(0L)
    private val profileInstall = MutableStateFlow<ProfileInstallUiState>(ProfileInstallUiState.Idle)
    private val rehearsal = MutableStateFlow<RehearsalActionUiState>(RehearsalActionUiState.Idle)
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

    val state: StateFlow<LenswakeUiState> = combine(
        scheduleRepository.observeSchedules(),
        profiles,
        diagnosticEvents,
        combine(profiles, preflightInvalidations) { currentProfiles, _ ->
            runtimePreflightProbe.inspect(currentProfiles)
        },
        combine(profileInstall, rehearsal) { install, action -> install to action },
    ) { schedules, currentProfiles, events, preflight, transientState ->
        LenswakeUiStateMapper.map(
            schedules = schedules,
            profiles = currentProfiles,
            events = events,
            preflight = preflight,
            profileInstall = transientState.first,
            rehearsal = transientState.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LenswakeUiState(),
    )

    fun refreshPreflight() {
        preflightRefresh.value += 1
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

    class Factory(
        private val scheduleRepository: ScheduleRepository,
        private val profileRepository: AutomationProfileRepository,
        private val executionRepository: ExecutionRepository,
        private val runtimePreflightProbe: RuntimePreflightProbe,
        private val installKnownPixelCameraProfile: InstallKnownPixelCameraProfile,
        private val rehearsalCoordinator: RehearsalCoordinator,
    ) : ViewModelProvider.Factory {
        constructor(graph: ApplicationGraph) : this(
            scheduleRepository = graph.scheduleRepository,
            profileRepository = graph.profileRepository,
            executionRepository = graph.executionRepository,
            runtimePreflightProbe = graph.runtimePreflightProbe,
            installKnownPixelCameraProfile = graph.installKnownPixelCameraProfile,
            rehearsalCoordinator = graph.rehearsalCoordinator,
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
                runtimePreflightProbe = runtimePreflightProbe,
                installKnownPixelCameraProfile = installKnownPixelCameraProfile,
                rehearsalCoordinator = rehearsalCoordinator,
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
        preflight: PreflightReport,
        profileInstall: ProfileInstallUiState = ProfileInstallUiState.Idle,
        rehearsal: RehearsalActionUiState = RehearsalActionUiState.Idle,
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
        profileInstall = profileInstall,
        rehearsal = rehearsal,
        actions = UiActionAvailability(
            canInstallCandidateProfile = profiles.isEmpty() && profileInstall !is ProfileInstallUiState.Installing &&
                profileInstall !is ProfileInstallUiState.Succeeded,
            installCandidateProfileUnavailableReason = when {
                profiles.isNotEmpty() -> "A Pixel Camera profile is already installed."
                profileInstall is ProfileInstallUiState.Installing -> "Candidate profile installation is in progress."
                profileInstall is ProfileInstallUiState.Succeeded -> "The candidate profile was installed."
                else -> "Candidate installation can be retried."
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
    )

    private val PreflightCheckType.displayName: String
        get() = when (this) {
            PreflightCheckType.EXACT_ALARMS -> "Exact alarms"
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
        return ScheduleSummaryUiState(
            id = schedule.id.value,
            title = schedule.name,
            timing = "$start - $stop",
            status = if (schedule.enabled) {
                "Enabled; alarm registration not verified"
            } else {
                "Disabled"
            },
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

    private val rehearsalRequiredChecks = setOf(
        PreflightCheckType.EXACT_ALARMS,
        PreflightCheckType.PIXEL_CAMERA_INSTALLED,
        PreflightCheckType.SECURE_CAMERA_RESOLVES,
        PreflightCheckType.ACCESSIBILITY_ENABLED,
        PreflightCheckType.ACCESSIBILITY_CONNECTED,
        PreflightCheckType.PROFILE_AVAILABLE,
    )
}
