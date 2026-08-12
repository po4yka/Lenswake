package dev.po4yka.lenswake.ui

import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.alarm.AlarmTransportFailureCode
import dev.po4yka.lenswake.application.AlarmTransportIncident
import dev.po4yka.lenswake.application.AlarmTransportIncidentAction
import dev.po4yka.lenswake.application.PixelCameraTemplateKind
import dev.po4yka.lenswake.application.SupportedPixelModelRegistry
import dev.po4yka.lenswake.core.AutomationEvent
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionSession
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightReport
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfilePersistenceIssue
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RehearsalVerificationPolicy
import dev.po4yka.lenswake.core.ScheduleReadiness
import dev.po4yka.lenswake.core.SessionKind
import dev.po4yka.lenswake.core.SessionStatus
import dev.po4yka.lenswake.core.supportedCaptureConfigurations
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal val DIAGNOSTIC_SESSION_ORDER: Comparator<ExecutionSession> =
    compareByDescending<ExecutionSession> { it.updatedAt }.thenBy { it.id.value }

internal object LenswakeActionAvailabilityMapper {
    fun map(
        profiles: List<PixelCameraProfile>,
        executions: List<ExecutionSession>,
        events: List<AutomationEvent>,
        incidents: List<AlarmTransportIncident>,
        profileIssues: List<ProfilePersistenceIssue>,
        preflight: PreflightReport,
        profileInstall: ProfileInstallUiState,
        rehearsal: RehearsalActionUiState,
        activeSession: ExecutionSession?,
        scheduleAction: ScheduleActionUiState,
        strings: UiStringProvider,
    ): UiActionAvailability = UiActionAvailability(
        canCreateSchedule = canCreateSchedule(profiles, executions, preflight, scheduleAction),
        createScheduleUnavailableReason = createScheduleUnavailableReason(
            profiles,
            executions,
            preflight,
            scheduleAction,
            strings,
        ),
        canInstallCandidateProfile = profileInstall !is ProfileInstallUiState.Installing &&
            profileInstall !is ProfileInstallUiState.Succeeded,
        installCandidateProfileUnavailableReason = installUnavailableReason(
            profileInstall,
            profiles,
            strings,
        ),
        canRunRehearsal = canRunRehearsal(profiles, preflight, rehearsal, activeSession),
        rehearsalUnavailableReason = rehearsalUnavailableReason(
            profiles,
            preflight,
            rehearsal,
            activeSession,
            strings,
        ),
        canExportDiagnostics = diagnosticsAvailable(executions, events, incidents, profileIssues),
        exportDiagnosticsUnavailableReason = if (diagnosticsAvailable(executions, events, incidents, profileIssues)) {
            ""
        } else {
            strings.get(R.string.action_diagnostics_empty_default)
        },
    )

    private fun canCreateSchedule(
        profiles: List<PixelCameraProfile>,
        executions: List<ExecutionSession>,
        preflight: PreflightReport,
        scheduleAction: ScheduleActionUiState,
    ): Boolean = preflight.hasAllScheduleChecksPassed() &&
        profiles.any { LenswakeProfileUiMapper.verifiedCaptures(it, executions).isNotEmpty() } &&
        scheduleAction !is ScheduleActionUiState.Working

    private fun createScheduleUnavailableReason(
        profiles: List<PixelCameraProfile>,
        executions: List<ExecutionSession>,
        preflight: PreflightReport,
        scheduleAction: ScheduleActionUiState,
        strings: UiStringProvider,
    ): String = when {
        !preflight.hasAllScheduleChecksPassed() -> strings.get(R.string.action_create_setup_required)
        profiles.none { LenswakeProfileUiMapper.verifiedCaptures(it, executions).isNotEmpty() } ->
            strings.get(R.string.action_create_profile_required)
        scheduleAction is ScheduleActionUiState.Working -> strings.get(R.string.action_create_busy)
        else -> strings.get(R.string.action_create_available)
    }

    private fun installUnavailableReason(
        profileInstall: ProfileInstallUiState,
        profiles: List<PixelCameraProfile>,
        strings: UiStringProvider,
    ): String = when {
        profileInstall is ProfileInstallUiState.Installing -> strings.get(R.string.action_profile_installing)
        profileInstall is ProfileInstallUiState.Succeeded -> strings.get(R.string.action_profile_installed)
        profiles.isEmpty() -> strings.get(R.string.action_profile_retry)
        else -> strings.get(R.string.action_profile_update_check)
    }

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
            LenswakeActiveSessionUiMapper.formatStopDeadline(activeSession),
        )
        rehearsal is RehearsalActionUiState.Running -> strings.get(R.string.action_rehearsal_running)
        profiles.isEmpty() -> strings.get(R.string.action_rehearsal_profile_required)
        else -> rehearsalRequiredChecks.firstNotNullOfOrNull { type ->
            preflight.checks.singleOrNull { it.type == type }
                ?.takeIf { it.status != PreflightStatus.PASSED }
                ?.message
        } ?: strings.get(R.string.action_rehearsal_ready)
    }

    private fun diagnosticsAvailable(
        executions: List<ExecutionSession>,
        events: List<AutomationEvent>,
        incidents: List<AlarmTransportIncident>,
        profileIssues: List<ProfilePersistenceIssue>,
    ): Boolean = executions.isNotEmpty() || events.isNotEmpty() || incidents.isNotEmpty() || profileIssues.isNotEmpty()

    private fun PreflightReport.hasAllScheduleChecksPassed(): Boolean =
        scheduleRequiredChecks.all { type ->
            checks.singleOrNull { it.type == type }?.let { check ->
                check.severity != PreflightSeverity.BLOCKING || check.status == PreflightStatus.PASSED
            } == true
        }

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

internal object LenswakeActiveSessionUiMapper {
    fun map(
        session: ExecutionSession,
        now: Instant,
        strings: UiStringProvider,
    ): ActiveSessionUiState = ActiveSessionUiState(
        sessionId = session.id.value,
        kind = when (session.kind) {
            SessionKind.SCHEDULED -> ActiveSessionKind.SCHEDULED
            SessionKind.REHEARSAL -> ActiveSessionKind.REHEARSAL
        },
        stopDeadline = session.expectedStopAt,
        title = title(session, strings),
        detail = strings.get(
            R.string.active_session_detail,
            session.id.value,
            formatStopDeadline(session),
        ),
        status = status(session, now, strings),
    )

    fun formatStopDeadline(session: ExecutionSession): String =
        session.expectedStopAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_ZONED_DATE_TIME)

    private fun title(session: ExecutionSession, strings: UiStringProvider): String = when (session.kind) {
        SessionKind.SCHEDULED -> session.scheduleName?.takeIf(String::isNotBlank)?.let { name ->
            strings.get(R.string.schedules_active_session_named_title, name)
        } ?: strings.get(R.string.schedules_active_session_title)
        SessionKind.REHEARSAL -> strings.get(R.string.profiles_active_rehearsal_title)
    }

    private fun status(
        session: ExecutionSession,
        now: Instant,
        strings: UiStringProvider,
    ): String = when {
        !session.expectedStopAt.isAfter(now) -> strings.get(R.string.status_stop_overdue)
        session.stopIsPending() -> strings.get(R.string.status_stop_pending)
        session.status in PREPARING_STATUSES -> strings.get(R.string.status_preparing)
        else -> strings.get(R.string.status_recording_expected)
    }

    private fun ExecutionSession.stopIsPending(): Boolean =
        status in STOP_PENDING_STATUSES || stopActionAt != null || alarmStopDeliveredAt != null

    private val STOP_PENDING_STATUSES = setOf(SessionStatus.STOPPING, SessionStatus.FAILED)
    private val PREPARING_STATUSES = setOf(SessionStatus.PENDING, SessionStatus.STARTING)
}

internal object LenswakeReadinessUiMapper {
    fun readiness(
        preflight: PreflightReport,
        strings: UiStringProvider,
    ): ReadinessUiState = when (val readiness = preflight.readiness) {
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

    fun capability(check: PreflightCheck, strings: UiStringProvider): CapabilityUiState = CapabilityUiState(
        name = strings.get(capabilityNames.getValue(check.type)),
        status = when (check.status) {
            PreflightStatus.PASSED -> CapabilityStatus.AVAILABLE
            PreflightStatus.FAILED -> CapabilityStatus.BLOCKED
            PreflightStatus.UNKNOWN -> CapabilityStatus.UNKNOWN
        },
        detail = check.message,
        required = check.severity == PreflightSeverity.BLOCKING,
        remediation = check.remediation,
    )

    fun initialCapabilities(strings: UiStringProvider): List<CapabilityUiState> = listOf(
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

    private val capabilityNames = mapOf(
        PreflightCheckType.EXACT_ALARMS to R.string.capability_exact_alarms,
        PreflightCheckType.NOTIFICATIONS to R.string.capability_notifications,
        PreflightCheckType.MEDIA_VIDEO_ACCESS to R.string.capability_media_video_access,
        PreflightCheckType.FULL_SCREEN_INTENT to R.string.capability_full_screen_intent,
        PreflightCheckType.PIXEL_CAMERA_INSTALLED to R.string.capability_pixel_camera_installed,
        PreflightCheckType.SECURE_CAMERA_RESOLVES to R.string.capability_secure_camera_launch,
        PreflightCheckType.DEVICE_WAKE to R.string.capability_device_wake,
        PreflightCheckType.ACCESSIBILITY_ENABLED to R.string.capability_accessibility_service,
        PreflightCheckType.ACCESSIBILITY_CONNECTED to R.string.capability_accessibility_connection,
        PreflightCheckType.PROFILE_AVAILABLE to R.string.capability_camera_profile,
        PreflightCheckType.PROFILE_COMPATIBILITY to R.string.capability_profile_compatibility,
        PreflightCheckType.REHEARSAL_CURRENT to R.string.capability_camera_test,
        PreflightCheckType.PRIVILEGED_FALLBACK to R.string.capability_privileged_fallback,
        PreflightCheckType.BATTERY to R.string.capability_battery,
        PreflightCheckType.CHARGING to R.string.capability_charging,
        PreflightCheckType.STORAGE to R.string.capability_storage,
    )
}

internal object LenswakeScheduleUiMapper {
    fun summary(schedule: RecordingSchedule, strings: UiStringProvider): ScheduleSummaryUiState {
        val formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
        val start = schedule.startAt.atZone(schedule.zoneId).format(formatter)
        val stop = schedule.stopAt.atZone(schedule.zoneId).format(formatter)
        return ScheduleSummaryUiState(
            id = schedule.id.value,
            title = schedule.name,
            timing = strings.get(R.string.schedule_time_range, start, stop),
            status = strings.get(if (schedule.enabled) R.string.status_enabled else R.string.status_disabled),
            startLocal = schedule.startAt.atZone(schedule.zoneId).toLocalDateTime(),
            stopLocal = schedule.stopAt.atZone(schedule.zoneId).toLocalDateTime(),
            zoneId = schedule.zoneId,
            capture = schedule.capture,
            profileId = schedule.profileId.value,
            experimentalRiskAccepted = schedule.experimentalRiskAccepted,
            enabled = schedule.enabled,
        )
    }
}

internal object LenswakeProfileUiMapper {
    fun summary(
        profile: PixelCameraProfile,
        executions: List<ExecutionSession>,
        strings: UiStringProvider,
    ): ProfileSummaryUiState {
        val environment = profile.environment
        val title = if (environment.deviceModel.startsWith(environment.deviceManufacturer, ignoreCase = true)) {
            environment.deviceModel
        } else {
            "${environment.deviceManufacturer} ${environment.deviceModel}"
        }
        val verifiedCaptures = verifiedCaptures(profile, executions)
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
            verifiedForScheduling = verifiedCaptures.isNotEmpty(),
            supportTier = profile.supportTier,
            supportedCaptures = verifiedCaptures,
            captureMatrix = captureMatrix(profile, executions),
        )
    }

    private fun captureMatrix(
        profile: PixelCameraProfile,
        executions: List<ExecutionSession>,
    ): List<CaptureMatrixRowUiState> {
        val configured = profile.supportedCaptureConfigurations()
        return buildList {
            LensSelection.entries.forEach { lens ->
                add(CaptureConfiguration.Video(lens))
                TimeLapseSpeed.entries.forEach { speed ->
                    add(CaptureConfiguration.TimeLapse(speed, lens))
                }
                add(CaptureConfiguration.NightSightTimeLapse(lens))
            }
        }.map { capture ->
            val matching = executions.filter { session ->
                session.profileId == profile.id && session.capture == capture &&
                    session.kind == SessionKind.REHEARSAL
            }
            val status = when {
                capture !in configured -> CaptureMatrixStatus.UNAVAILABLE
                matching.any { RehearsalVerificationPolicy.qualifies(it, profile, capture) } ->
                    CaptureMatrixStatus.VERIFIED_LOCALLY
                matching.maxByOrNull(ExecutionSession::updatedAt)?.let { latest ->
                    latest.status == SessionStatus.FAILED && latest.failure?.code in unavailableCaptureFailures
                } == true -> CaptureMatrixStatus.UNAVAILABLE
                matching.maxByOrNull(ExecutionSession::updatedAt)?.status == SessionStatus.FAILED ->
                    CaptureMatrixStatus.FAILED
                else -> CaptureMatrixStatus.UNTESTED
            }
            CaptureMatrixRowUiState(capture, status)
        }
    }

    private val unavailableCaptureFailures = setOf(
        AutomationFailureCode.VIDEO_MODE_NOT_FOUND,
        AutomationFailureCode.TIME_LAPSE_MODE_NOT_FOUND,
        AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND,
        AutomationFailureCode.TIME_LAPSE_SPEED_NOT_FOUND,
        AutomationFailureCode.LENS_NOT_FOUND,
        AutomationFailureCode.UNSUPPORTED_CAPTURE_CONFIGURATION,
    )

    fun verifiedCaptures(
        profile: PixelCameraProfile,
        executions: List<ExecutionSession>,
    ): Set<CaptureConfiguration> =
        if (profile.compatibility != ProfileCompatibility.VERIFIED || profile.verifiedAt == null) {
            emptySet()
        } else {
            profile.supportedCaptureConfigurations().filterTo(linkedSetOf()) { capture ->
                executions.any { session ->
                    RehearsalVerificationPolicy.qualifies(session, profile, capture)
                }
            }
        }
}

internal object LenswakeDiagnosticsUiMapper {
    fun sessions(
        executions: List<ExecutionSession>,
        events: List<AutomationEvent>,
        strings: UiStringProvider,
    ): List<DiagnosticSessionUiState> {
        val eventsBySession = events.groupBy(AutomationEvent::sessionId)
        return executions
            .sortedWith(DIAGNOSTIC_SESSION_ORDER)
            .take(MAX_SESSION_COUNT)
            .map { session -> sessionSummary(session, eventsBySession[session.id].orEmpty(), strings) }
    }

    private fun sessionSummary(
        session: ExecutionSession,
        events: List<AutomationEvent>,
        strings: UiStringProvider,
    ): DiagnosticSessionUiState {
        val orderedEvents = events.sortedWith(
            compareBy<AutomationEvent> { it.timestamp }
                .thenBy { it.sequence ?: Long.MAX_VALUE }
                .thenBy { it.id.value },
        )
        val selectorConfidences = orderedEvents.mapNotNull(::selectorConfidence)
        return DiagnosticSessionUiState(
            id = session.id.value,
            title = session.scheduleName?.takeIf(String::isNotBlank) ?: strings.get(
                when (session.kind) {
                    SessionKind.SCHEDULED -> R.string.diagnostics_scheduled_session_title
                    SessionKind.REHEARSAL -> R.string.diagnostics_rehearsal_session_title
                },
            ),
            detail = strings.get(
                R.string.diagnostics_session_detail,
                session.expectedStartAt.atOffset(ZoneOffset.UTC).format(eventTimeFormatter),
                session.id.value,
            ),
            status = session.status.name,
            duration = formatDuration(sessionDuration(session), strings),
            metrics = DiagnosticSessionMetricsUiState(
                retryCount = orderedEvents.count { it.outcome == dev.po4yka.lenswake.core.AutomationOutcome.RETRYING },
                fallbackCount = orderedEvents.count { it.interactionMethod in fallbackMethods },
                privilegedFallbackCount = orderedEvents.count {
                    it.interactionMethod == InteractionMethod.PRIVILEGED_INPUT
                },
                selectorConfidence = selectorConfidences.minWithOrNull(
                    compareBy<DiagnosticSelectorConfidenceUiState> {
                        it.score.toDouble() / it.minimumScore.coerceAtLeast(1)
                    }.thenBy { it.score },
                ),
            ),
            timeline = orderedEvents.map { event -> eventSummary(event, strings) },
        )
    }

    private fun eventSummary(
        event: AutomationEvent,
        strings: UiStringProvider,
    ): DiagnosticTimelineEventUiState {
        val operation = event.operation
        val failure = event.failure
        val detail = when {
            operation != null && failure != null -> strings.get(
                R.string.diagnostics_event_operation_failure_detail,
                event.state.name,
                event.outcome.name,
                operation.name,
                failure.code.name,
            )
            operation != null -> strings.get(
                R.string.diagnostics_event_operation_detail,
                event.state.name,
                event.outcome.name,
                operation.name,
            )
            failure != null -> strings.get(
                R.string.diagnostics_event_failure_detail,
                event.state.name,
                event.outcome.name,
                failure.code.name,
            )
            else -> strings.get(
                R.string.diagnostics_event_detail,
                event.state.name,
                event.outcome.name,
            )
        }
        return DiagnosticTimelineEventUiState(
            id = event.id.value,
            title = strings.get(R.string.diagnostics_event_title, event.name),
            detail = detail,
            occurredAt = event.timestamp.atOffset(ZoneOffset.UTC).format(eventTimeFormatter),
            duration = event.durationMs?.let { formatDuration(Duration.ofMillis(it), strings) },
            interactionMethod = event.interactionMethod?.name,
            attempt = event.attempt,
            selectorConfidence = selectorConfidence(event),
            selectorMatch = selectorMatch(event, strings),
        )
    }

    private fun sessionDuration(session: ExecutionSession): Duration {
        val start = session.recordingVerifiedAt ?: session.createdAt
        val end = session.stoppedVerifiedAt ?: session.updatedAt
        return Duration.between(start, end).coerceAtLeast(Duration.ZERO)
    }

    private fun selectorConfidence(event: AutomationEvent): DiagnosticSelectorConfidenceUiState? {
        val values = event.failure?.context.orEmpty() + event.metadata
        val score = (values[SELECTOR_SCORE] ?: values[BEST_SCORE])?.toIntOrNull()
        val minimumScore = (values[SELECTOR_MINIMUM_SCORE] ?: values[MINIMUM_SCORE])?.toIntOrNull()
        return if (score != null && minimumScore != null) {
            DiagnosticSelectorConfidenceUiState(score = score, minimumScore = minimumScore)
        } else {
            null
        }
    }

    private fun selectorMatch(event: AutomationEvent, strings: UiStringProvider): String? {
        val index = event.metadata[SELECTOR_INDEX]?.toIntOrNull() ?: return null
        val signals = event.metadata[SELECTOR_SIGNALS]
        return if (signals.isNullOrBlank()) {
            strings.get(R.string.diagnostics_selector_match_index, index)
        } else {
            strings.get(R.string.diagnostics_selector_match_signals, index, signals)
        }
    }

    private fun formatDuration(duration: Duration, strings: UiStringProvider): String {
        val millis = duration.toMillis()
        val seconds = duration.seconds
        val minutes = seconds / SECONDS_PER_MINUTE
        val hours = minutes / MINUTES_PER_HOUR
        return when {
            hours > 0 -> strings.get(
                R.string.diagnostics_duration_hours_minutes,
                hours,
                minutes % MINUTES_PER_HOUR,
            )
            minutes > 0 -> strings.get(
                R.string.diagnostics_duration_minutes_seconds,
                minutes,
                seconds % SECONDS_PER_MINUTE,
            )
            seconds > 0 -> strings.get(R.string.diagnostics_duration_seconds, seconds)
            else -> strings.get(R.string.diagnostics_duration_milliseconds, millis)
        }
    }

    fun incidentSummary(
        incident: AlarmTransportIncident,
        strings: UiStringProvider,
    ): AlarmTransportIncidentUiState = AlarmTransportIncidentUiState(
        id = incident.id,
        title = strings.get(incident.titleResource()),
        detail = strings.get(incident.detailResource()),
        occurredAt = Instant.ofEpochMilli(incident.recordedAtEpochMillis)
            .atOffset(ZoneOffset.UTC)
            .format(eventTimeFormatter),
        action = when (incident.action) {
            AlarmTransportIncidentAction.OPEN_PIXEL_CAMERA -> AlarmTransportIncidentUiAction.OPEN_PIXEL_CAMERA
            null -> null
        },
    )

    fun profilePersistenceIssueSummary(
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

    private fun AlarmTransportIncident.titleResource(): Int = when {
        code == AlarmTransportFailureCode.JOURNAL_ENTRY_CORRUPT -> R.string.alarm_journal_failure_title
        code in recoveryFailureCodes -> R.string.alarm_recovery_failure_title
        action == AlarmTransportIncidentAction.OPEN_PIXEL_CAMERA -> R.string.alarm_stop_failure_title
        else -> R.string.alarm_start_failure_title
    }

    private fun AlarmTransportIncident.detailResource(): Int = when {
        code == AlarmTransportFailureCode.JOURNAL_ENTRY_CORRUPT -> R.string.alarm_journal_failure_message
        code in recoveryFailureCodes -> R.string.alarm_recovery_failure_message
        action == AlarmTransportIncidentAction.OPEN_PIXEL_CAMERA -> R.string.alarm_stop_failure_message
        else -> R.string.alarm_start_failure_message
    }

    private val eventTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val recoveryFailureCodes = setOf(
        AlarmTransportFailureCode.RECOVERY_ATTEMPTS_EXHAUSTED,
        AlarmTransportFailureCode.RECOVERY_REQUEUE_FAILED,
        AlarmTransportFailureCode.RECOVERY_CAPABILITY_UNAVAILABLE,
    )
    private val fallbackMethods = setOf(
        InteractionMethod.ACCESSIBILITY_NODE_GESTURE,
        InteractionMethod.ACCESSIBILITY_PROFILE_GESTURE,
    )
    private const val MAX_SESSION_COUNT = 10
    private const val MAX_VISIBLE_PERSISTENCE_KEY_LENGTH = 64
    private const val SELECTOR_SCORE = "selectorScore"
    private const val SELECTOR_MINIMUM_SCORE = "selectorMinimumScore"
    private const val BEST_SCORE = "bestScore"
    private const val MINIMUM_SCORE = "minimumScore"
    private const val SELECTOR_INDEX = "selectorIndex"
    private const val SELECTOR_SIGNALS = "selectorSignals"
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
}
