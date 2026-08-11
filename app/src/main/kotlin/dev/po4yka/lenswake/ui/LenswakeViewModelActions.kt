package dev.po4yka.lenswake.ui

import android.util.Log
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.application.InstallKnownPixelCameraProfile
import dev.po4yka.lenswake.application.RehearsalCoordinator
import dev.po4yka.lenswake.application.ScheduleOperation
import dev.po4yka.lenswake.application.ScheduleWorkflow
import dev.po4yka.lenswake.application.ScheduleWorkflowFailureCode
import dev.po4yka.lenswake.application.ScheduleWorkflowResult
import dev.po4yka.lenswake.application.qualifiesRehearsal
import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RehearsalRequest
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.supportedCaptureConfigurations
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

interface LenswakeProfileActions {
    fun installCandidateProfile()

    fun runProfileRehearsal(profileId: String)

    fun runScheduleRehearsal(scheduleId: String)
}

interface LenswakeScheduleActions {
    fun beginCreateSchedule()

    fun beginEditSchedule(scheduleId: String)

    fun updateScheduleForm(form: ScheduleFormUiState)

    fun cancelScheduleEditor()

    fun submitSchedule()

    fun setScheduleEnabled(scheduleId: String, enabled: Boolean)

    fun requestDeleteSchedule(scheduleId: String)

    fun cancelDeleteSchedule()

    fun confirmDeleteSchedule(scheduleId: String)

    fun clearScheduleOutcome()
}

internal class LenswakeViewModelActionState {
    val preflightRefresh = MutableStateFlow(0L)
    val profileInstall = MutableStateFlow<ProfileInstallUiState>(ProfileInstallUiState.Idle)
    val rehearsal = MutableStateFlow<RehearsalActionUiState>(RehearsalActionUiState.Idle)
    val rehearsalTarget = MutableStateFlow<RehearsalTargetUiState?>(null)
    val scheduleEditor = MutableStateFlow<ScheduleEditorUiState>(ScheduleEditorUiState.Closed)
    val scheduleAction = MutableStateFlow<ScheduleActionUiState>(ScheduleActionUiState.Idle)
    val pendingDeleteScheduleId = MutableStateFlow<String?>(null)
    val setupRemediationMessage = MutableStateFlow<String?>(null)

    lateinit var state: StateFlow<LenswakeUiState>
        private set
    lateinit var scope: CoroutineScope
        private set

    fun bind(
        state: StateFlow<LenswakeUiState>,
        scope: CoroutineScope,
    ) {
        this.state = state
        this.scope = scope
    }

    fun refreshPreflight() {
        preflightRefresh.value += 1
    }
}

internal class LenswakeProfileActionsImpl(
    private val actionState: LenswakeViewModelActionState,
    private val profileRepository: AutomationProfileRepository,
    private val scheduleRepository: ScheduleRepository,
    private val executions: ExecutionRepository,
    private val installKnownPixelCameraProfile: InstallKnownPixelCameraProfile,
    private val rehearsalCoordinator: RehearsalCoordinator,
    private val strings: UiStringProvider,
) : LenswakeProfileActions {
    override fun installCandidateProfile() {
        if (actionState.profileInstall.value == ProfileInstallUiState.Installing) return

        actionState.profileInstall.value = ProfileInstallUiState.Installing
        actionState.scope.launch {
            actionState.profileInstall.value = installKnownPixelCameraProfile().toUiState(strings)
        }
    }

    override fun runProfileRehearsal(profileId: String) {
        launchRehearsal(RehearsalTargetUiState.Profile(profileId)) {
            val profile = profileRepository.get(ProfileId(profileId))
                ?: return@launchRehearsal RehearsalPreparation.Failed(
                    strings.get(R.string.rehearsal_profile_required),
                )
            val supportedCaptures = profile.supportedCaptureConfigurations()
                .sortedWith(capturePreferenceComparator)
            val completedRehearsals = executions.observeExecutions().first()
            val capture = supportedCaptures.firstOrNull { candidate ->
                completedRehearsals.none { it.qualifiesRehearsal(profile, candidate) }
            } ?: supportedCaptures.firstOrNull()
                ?: return@launchRehearsal RehearsalPreparation.Failed(
                    strings.get(R.string.schedule_error_capture_not_supported),
                )
            RehearsalPreparation.Ready(
                RehearsalRequest(
                    profileId = profile.id,
                    capture = capture,
                    recordingDuration = Duration.ofSeconds(REHEARSAL_DURATION_SECONDS),
                ),
            )
        }
    }

    override fun runScheduleRehearsal(scheduleId: String) {
        launchRehearsal(RehearsalTargetUiState.Schedule(scheduleId)) {
            val schedule = scheduleRepository.get(ScheduleId(scheduleId))
                ?: return@launchRehearsal RehearsalPreparation.Failed(
                    strings.get(R.string.rehearsal_schedule_missing),
                )
            RehearsalPreparation.Ready(
                RehearsalRequest(
                    profileId = schedule.profileId,
                    capture = schedule.capture,
                    recordingDuration = Duration.ofSeconds(REHEARSAL_DURATION_SECONDS),
                    scheduleId = schedule.id,
                ),
            )
        }
    }

    private fun launchRehearsal(
        target: RehearsalTargetUiState,
        prepare: suspend () -> RehearsalPreparation,
    ) {
        if (
            !actionState.state.value.actions.canRunRehearsal ||
            actionState.rehearsal.value == RehearsalActionUiState.Running
        ) {
            return
        }

        actionState.rehearsalTarget.value = target
        actionState.rehearsal.value = RehearsalActionUiState.Running
        actionState.scope.launch {
            val attempt = runCatching {
                when (val preparation = prepare()) {
                    is RehearsalPreparation.Failed ->
                        RehearsalActionUiState.Failed(preparation.message)
                    is RehearsalPreparation.Ready ->
                        rehearsalCoordinator.run(preparation.request).toUiState(strings)
                }
            }
            val failure = attempt.exceptionOrNull()
            when (failure) {
                is CancellationException -> throw failure
                null -> actionState.rehearsal.value = checkNotNull(attempt.getOrNull())
                !is Exception -> throw failure
                else -> {
                    Log.e(TAG, "Unexpected rehearsal action failure", failure)
                    actionState.rehearsal.value = RehearsalActionUiState.Failed(
                        strings.get(R.string.rehearsal_unexpected_failure),
                    )
                }
            }
            actionState.refreshPreflight()
        }
    }
}

internal class LenswakeScheduleActionsImpl(
    private val actionState: LenswakeViewModelActionState,
    private val scheduleWorkflow: ScheduleWorkflow,
    private val strings: UiStringProvider,
    private val clock: LenswakeClock,
) : LenswakeScheduleActions {
    override fun beginCreateSchedule() {
        if (!actionState.state.value.actions.canCreateSchedule) {
            actionState.scheduleAction.value = ScheduleActionUiState.Failed(
                actionState.state.value.actions.createScheduleUnavailableReason,
            )
            return
        }
        val profile = actionState.state.value.profiles.firstOrNull { it.verifiedForScheduling }
        if (profile == null) {
            actionState.scheduleAction.value = ScheduleActionUiState.Failed(
                strings.get(R.string.action_create_profile_required),
            )
            return
        }
        actionState.scheduleAction.value = ScheduleActionUiState.Idle
        val zoneId = ZoneId.systemDefault()
        val startLocal = defaultScheduleStart(clock.now(), zoneId)
        val defaultCapture = profile.supportedCaptures
            .sortedWith(capturePreferenceComparator)
            .firstOrNull()
        if (defaultCapture == null) {
            actionState.scheduleAction.value = ScheduleActionUiState.Failed(
                strings.get(R.string.schedule_error_capture_not_supported),
            )
            return
        }
        actionState.scheduleEditor.value = ScheduleEditorUiState.Open(
            mode = ScheduleEditorMode.Create,
            form = ScheduleFormUiState(
                name = strings.get(R.string.default_schedule_name),
                startLocal = startLocal,
                stopLocal = startLocal.plusHours(DEFAULT_RECORDING_DURATION_HOURS),
                captureMode = defaultCapture.mode,
                timeLapseSpeed = defaultCapture.timeLapseSpeed ?: TimeLapseSpeed.X120,
                lens = defaultCapture.lens,
                profileId = profile.id,
                zoneId = zoneId,
            ),
        )
    }

    override fun beginEditSchedule(scheduleId: String) {
        val schedule = actionState.state.value.schedules.firstOrNull { it.id == scheduleId }
        if (schedule == null) {
            actionState.scheduleAction.value = ScheduleActionUiState.Failed(
                strings.get(R.string.schedule_selected_missing),
            )
            return
        }
        actionState.scheduleAction.value = ScheduleActionUiState.Idle
        actionState.scheduleEditor.value = ScheduleEditorUiState.Open(
            mode = ScheduleEditorMode.Edit(scheduleId),
            form = ScheduleFormUiState(
                name = schedule.title,
                startLocal = schedule.startLocal,
                stopLocal = schedule.stopLocal,
                zoneId = schedule.zoneId,
                captureMode = schedule.capture.mode,
                timeLapseSpeed = schedule.capture.timeLapseSpeed ?: TimeLapseSpeed.X120,
                lens = schedule.capture.lens,
                profileId = schedule.profileId,
                enabled = schedule.enabled,
            ),
        )
    }

    override fun updateScheduleForm(form: ScheduleFormUiState) {
        val editor = actionState.scheduleEditor.value as? ScheduleEditorUiState.Open ?: return
        actionState.scheduleEditor.value = editor.copy(form = form, error = null)
    }

    override fun cancelScheduleEditor() {
        if (actionState.scheduleAction.value is ScheduleActionUiState.Working) return
        actionState.scheduleEditor.value = ScheduleEditorUiState.Closed
    }

    override fun submitSchedule() {
        val editor = actionState.scheduleEditor.value as? ScheduleEditorUiState.Open ?: return
        if (actionState.scheduleAction.value is ScheduleActionUiState.Working) return
        val command = editor.form.toCommandOrNull()
        if (command == null) {
            actionState.scheduleEditor.value = editor.copy(
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

    override fun setScheduleEnabled(scheduleId: String, enabled: Boolean) {
        launchScheduleMutation(
            strings.get(if (enabled) R.string.schedule_enabling else R.string.schedule_disabling),
        ) {
            scheduleWorkflow.setEnabled(ScheduleId(scheduleId), enabled)
        }
    }

    override fun requestDeleteSchedule(scheduleId: String) {
        if (actionState.scheduleAction.value is ScheduleActionUiState.Working) return
        actionState.pendingDeleteScheduleId.value = scheduleId
    }

    override fun cancelDeleteSchedule() {
        actionState.pendingDeleteScheduleId.value = null
    }

    override fun confirmDeleteSchedule(scheduleId: String) {
        if (actionState.pendingDeleteScheduleId.value != scheduleId) return
        if (actionState.scheduleAction.value is ScheduleActionUiState.Working) return
        actionState.pendingDeleteScheduleId.value = null
        launchScheduleMutation(strings.get(R.string.schedule_deleting)) {
            scheduleWorkflow.delete(ScheduleId(scheduleId))
        }
    }

    override fun clearScheduleOutcome() {
        if (actionState.scheduleAction.value !is ScheduleActionUiState.Working) {
            actionState.scheduleAction.value = ScheduleActionUiState.Idle
        }
    }

    private fun launchScheduleMutation(
        message: String,
        operation: suspend () -> ScheduleWorkflowResult,
    ) {
        if (actionState.scheduleAction.value is ScheduleActionUiState.Working) return
        actionState.scheduleAction.value = ScheduleActionUiState.Working(message)
        actionState.scope.launch {
            val attempt = runCatching { operation() }
            val failure = attempt.exceptionOrNull()
            when (failure) {
                is CancellationException -> throw failure
                null -> {
                    val result = checkNotNull(attempt.getOrNull())
                    actionState.scheduleAction.value = result.toUiState(strings)
                    if (result is ScheduleWorkflowResult.Applied || result is ScheduleWorkflowResult.Deleted) {
                        actionState.scheduleEditor.value = ScheduleEditorUiState.Closed
                        actionState.pendingDeleteScheduleId.value = null
                        actionState.refreshPreflight()
                    }
                }
                !is Exception -> throw failure
                else -> {
                    Log.e(TAG, "Unexpected schedule action failure", failure)
                    actionState.scheduleAction.value = ScheduleActionUiState.Failed(
                        strings.get(R.string.schedule_change_failed),
                    )
                }
            }
        }
    }
}

private sealed interface RehearsalPreparation {
    data class Ready(val request: RehearsalRequest) : RehearsalPreparation
    data class Failed(val message: String) : RehearsalPreparation
}

private fun ScheduleFormUiState.toCommandOrNull() = runCatching {
    val start = requireNotNull(startLocal)
    val stop = requireNotNull(stopLocal)
    require(name.isNotBlank())
    require(profileId.isNotBlank())
    dev.po4yka.lenswake.application.ScheduleCommand(
        name = name.trim(),
        startAt = start.toUnambiguousInstant(zoneId),
        stopAt = stop.toUnambiguousInstant(zoneId),
        zoneId = zoneId,
        capture = captureConfiguration(),
        profileId = ProfileId(profileId.trim()),
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
        message = strings.get(code.messageResource()),
    )
    is ScheduleWorkflowResult.Failed -> ScheduleActionUiState.Failed(
        message = strings.get(code.messageResource()),
        rollbackFailures = if (rollbackFailures.isEmpty()) {
            emptyList()
        } else {
            listOf(strings.get(R.string.schedule_error_rollback_incomplete))
        },
    )
}

private fun ScheduleWorkflowFailureCode.messageResource(): Int =
    scheduleWorkflowFailureMessages.getValue(this)

private val scheduleWorkflowFailureMessages = mapOf(
    ScheduleWorkflowFailureCode.SCHEDULE_NOT_FOUND to R.string.schedule_error_not_found,
    ScheduleWorkflowFailureCode.PROFILE_NOT_FOUND to R.string.schedule_error_profile_not_found,
    ScheduleWorkflowFailureCode.PROFILE_NOT_VERIFIED to R.string.schedule_error_profile_not_verified,
    ScheduleWorkflowFailureCode.CAPTURE_NOT_SUPPORTED to R.string.schedule_error_capture_not_supported,
    ScheduleWorkflowFailureCode.RUNTIME_NOT_READY to R.string.schedule_error_runtime_not_ready,
    ScheduleWorkflowFailureCode.PREFLIGHT_FAILED to R.string.schedule_error_preflight_failed,
    ScheduleWorkflowFailureCode.INVALID_SCHEDULE to R.string.schedule_error_invalid,
    ScheduleWorkflowFailureCode.SCHEDULE_EXECUTION_ACTIVE to R.string.schedule_error_execution_active,
    ScheduleWorkflowFailureCode.EXECUTION_STATE_UNAVAILABLE to R.string.schedule_error_execution_unavailable,
    ScheduleWorkflowFailureCode.CANCEL_FAILED to R.string.schedule_error_cancel_failed,
    ScheduleWorkflowFailureCode.PERSIST_FAILED to R.string.schedule_error_persist_failed,
    ScheduleWorkflowFailureCode.START_ALARM_FAILED to R.string.schedule_error_start_alarm_failed,
    ScheduleWorkflowFailureCode.STOP_ALARM_FAILED to R.string.schedule_error_stop_alarm_failed,
    ScheduleWorkflowFailureCode.DELETE_FAILED to R.string.schedule_error_delete_failed,
)

internal val capturePreferenceComparator = compareBy<CaptureConfiguration>(
    {
        when (it.mode) {
            dev.po4yka.lenswake.core.CaptureMode.TIME_LAPSE -> 0
            dev.po4yka.lenswake.core.CaptureMode.VIDEO -> 1
            dev.po4yka.lenswake.core.CaptureMode.NIGHT_SIGHT_TIME_LAPSE -> 2
        }
    },
    { it.lens.ordinal },
    { it.timeLapseSpeed?.ordinal ?: -1 },
)

private const val TAG = "LenswakeViewModel"
private const val REHEARSAL_DURATION_SECONDS = 10L
private const val DEFAULT_RECORDING_DURATION_HOURS = 1L
