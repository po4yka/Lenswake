package dev.po4yka.lenswake.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleReadiness
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.application.RuntimePreflightProbe
import dev.po4yka.lenswake.di.ApplicationGraph
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class LenswakeViewModel(
    scheduleRepository: ScheduleRepository,
    profileRepository: AutomationProfileRepository,
    executionRepository: ExecutionRepository,
    private val runtimePreflightProbe: RuntimePreflightProbe,
) : ViewModel() {
    private val preflightRefresh = MutableStateFlow(0L)
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
    ) { schedules, currentProfiles, events, preflight ->
        LenswakeUiStateMapper.map(schedules, currentProfiles, events, preflight)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LenswakeUiState(),
    )

    fun refreshPreflight() {
        preflightRefresh.value += 1
    }

    class Factory(
        private val scheduleRepository: ScheduleRepository,
        private val profileRepository: AutomationProfileRepository,
        private val executionRepository: ExecutionRepository,
        private val runtimePreflightProbe: RuntimePreflightProbe,
    ) : ViewModelProvider.Factory {
        constructor(graph: ApplicationGraph) : this(
            scheduleRepository = graph.scheduleRepository,
            profileRepository = graph.profileRepository,
            executionRepository = graph.executionRepository,
            runtimePreflightProbe = graph.runtimePreflightProbe,
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
            ) as T
        }
    }

    private companion object {
        const val MAX_OBSERVED_SESSIONS = 10
        const val MAX_VISIBLE_EVENTS = 50
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
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
        actions = UiActionAvailability(
            canExportDiagnostics = false,
            exportDiagnosticsUnavailableReason = if (events.isEmpty()) {
                "There are no diagnostic events to export."
            } else {
                "Diagnostic export is not implemented yet."
            },
        ),
    )

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
}
