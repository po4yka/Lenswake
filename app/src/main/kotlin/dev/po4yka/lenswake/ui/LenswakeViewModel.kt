package dev.po4yka.lenswake.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.di.ApplicationGraph
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class LenswakeViewModel(
    scheduleRepository: ScheduleRepository,
    profileRepository: AutomationProfileRepository,
    executionRepository: ExecutionRepository,
) : ViewModel() {
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
        profileRepository.observeProfiles(),
        diagnosticEvents,
    ) { schedules, profiles, events ->
        LenswakeUiStateMapper.map(schedules, profiles, events)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LenswakeUiState(),
    )

    class Factory(
        private val scheduleRepository: ScheduleRepository,
        private val profileRepository: AutomationProfileRepository,
        private val executionRepository: ExecutionRepository,
    ) : ViewModelProvider.Factory {
        constructor(graph: ApplicationGraph) : this(
            scheduleRepository = graph.scheduleRepository,
            profileRepository = graph.profileRepository,
            executionRepository = graph.executionRepository,
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
    ): LenswakeUiState = LenswakeUiState(
        readiness = blockedReadiness(profiles),
        schedules = schedules
            .sortedBy { it.startAt }
            .map(::scheduleSummary),
        profiles = profiles
            .sortedWith(compareBy({ it.environment.deviceModel }, { it.id.value }))
            .map(::profileSummary),
        capabilities = capabilities(profiles),
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

    private fun blockedReadiness(profiles: List<PixelCameraProfile>): ReadinessUiState.Blocked =
        ReadinessUiState.Blocked(
            title = "Setup required",
            summary = if (profiles.isEmpty()) {
                "Scheduling is disabled until exact alarms, Accessibility, and a verified Pixel Camera profile are available."
            } else {
                "Persisted profiles were loaded, but current-device compatibility, Accessibility, exact alarms, and rehearsal are not all verified."
            },
        )

    private fun capabilities(profiles: List<PixelCameraProfile>): List<CapabilityUiState> = listOf(
        CapabilityUiState(
            name = "Exact alarms",
            status = CapabilityStatus.UNKNOWN,
            detail = "Exact-alarm access has not been checked.",
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
            detail = if (profiles.isEmpty()) {
                "No profile is persisted for this environment."
            } else {
                "${profiles.size} profile(s) persisted; compatibility with the current environment has not been checked."
            },
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
                ProfileCompatibility.VERIFIED -> "Persisted as verified; current environment not checked"
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
