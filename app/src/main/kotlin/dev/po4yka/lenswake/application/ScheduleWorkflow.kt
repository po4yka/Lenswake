package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.ScheduleValidationError
import dev.po4yka.lenswake.core.ScheduleValidator
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScheduleCommand(
    val name: String,
    val startAt: Instant,
    val stopAt: Instant,
    val zoneId: ZoneId,
    val capture: CaptureConfiguration,
    val profileId: ProfileId,
    val enabled: Boolean,
)

enum class ScheduleOperation {
    CREATED,
    UPDATED,
    ENABLED,
    DISABLED,
}

enum class ScheduleWorkflowFailureCode {
    SCHEDULE_NOT_FOUND,
    PROFILE_NOT_FOUND,
    PROFILE_NOT_VERIFIED,
    CAPTURE_NOT_SUPPORTED,
    RUNTIME_NOT_READY,
    PREFLIGHT_FAILED,
    INVALID_SCHEDULE,
    SCHEDULE_EXECUTION_ACTIVE,
    EXECUTION_STATE_UNAVAILABLE,
    CANCEL_FAILED,
    PERSIST_FAILED,
    START_ALARM_FAILED,
    STOP_ALARM_FAILED,
    DELETE_FAILED,
}

sealed interface ScheduleWorkflowResult {
    data class Applied(
        val operation: ScheduleOperation,
        val schedule: RecordingSchedule,
    ) : ScheduleWorkflowResult

    data class Deleted(
        val scheduleId: ScheduleId,
    ) : ScheduleWorkflowResult

    data class Rejected(
        val code: ScheduleWorkflowFailureCode,
        val message: String,
        val validationErrors: Set<ScheduleValidationError> = emptySet(),
    ) : ScheduleWorkflowResult

    data class Failed(
        val code: ScheduleWorkflowFailureCode,
        val message: String,
        val rollbackFailures: List<String> = emptyList(),
    ) : ScheduleWorkflowResult
}

/**
 * Owns the compensating transaction between durable schedules and Android exact alarms.
 *
 * The repository is always the source of truth. An enabled schedule is reported as applied only
 * after both its independent START and STOP alarms have been registered. A partial registration
 * is cancelled and the previous durable/alarm state is restored before a failure is returned.
 */
class ScheduleWorkflow(
    scheduleRepository: ScheduleRepository,
    executionRepository: ExecutionRepository,
    profileRepository: AutomationProfileRepository,
    scheduler: RecordingScheduler,
    private val clock: LenswakeClock,
    preflightProbe: RuntimePreflightProbe,
    validator: ScheduleValidator = ScheduleValidator(),
    private val mutationMutex: Mutex = Mutex(),
) {
    private val mutationGuard = ScheduleMutationGuard(scheduleRepository, executionRepository)
    private val readiness = ScheduleReadiness(profileRepository, executionRepository, preflightProbe, validator)
    private val transaction = ScheduleMutationTransaction(scheduleRepository, scheduler)

    suspend fun create(command: ScheduleCommand): ScheduleWorkflowResult =
        mutationMutex.withLock { createLocked(command) }

    suspend fun edit(
        scheduleId: ScheduleId,
        command: ScheduleCommand,
    ): ScheduleWorkflowResult = mutationMutex.withLock { editLocked(scheduleId, command) }

    suspend fun setEnabled(
        scheduleId: ScheduleId,
        enabled: Boolean,
    ): ScheduleWorkflowResult = mutationMutex.withLock { setEnabledLocked(scheduleId, enabled) }

    suspend fun delete(scheduleId: ScheduleId): ScheduleWorkflowResult =
        mutationMutex.withLock { deleteLocked(scheduleId) }

    private suspend fun createLocked(command: ScheduleCommand): ScheduleWorkflowResult {
        val now = clock.now()
        val schedule = RecordingSchedule(
            id = ScheduleId.new(),
            name = command.name.trim(),
            startAt = command.startAt,
            stopAt = command.stopAt,
            zoneId = command.zoneId,
            capture = command.capture,
            profileId = command.profileId,
            enabled = command.enabled,
            createdAt = now,
            updatedAt = now,
        )
        return readiness.failure(schedule, now) ?: withContext(NonCancellable) {
            transaction.replace(previous = null, candidate = schedule, operation = ScheduleOperation.CREATED)
        }
    }

    private suspend fun editLocked(
        scheduleId: ScheduleId,
        command: ScheduleCommand,
    ): ScheduleWorkflowResult = when (val lookup = mutationGuard.load(scheduleId)) {
        is ScheduleLookup.Failed -> lookup.result
        is ScheduleLookup.Found -> mutationGuard.pixelCameraOwnerBlock(scheduleId, "edit") ?: run {
            val now = clock.now()
            val candidate = lookup.schedule.copy(
                name = command.name.trim(),
                startAt = command.startAt,
                stopAt = command.stopAt,
                zoneId = command.zoneId,
                capture = command.capture,
                profileId = command.profileId,
                enabled = command.enabled,
                updatedAt = nextScheduleRevision(now, lookup.schedule.updatedAt),
            )
            readiness.failure(candidate, now) ?: withContext(NonCancellable) {
                transaction.replace(lookup.schedule, candidate, ScheduleOperation.UPDATED)
            }
        }
    }

    private suspend fun setEnabledLocked(
        scheduleId: ScheduleId,
        enabled: Boolean,
    ): ScheduleWorkflowResult = when (val lookup = mutationGuard.load(scheduleId)) {
        is ScheduleLookup.Failed -> lookup.result
        is ScheduleLookup.Found -> if (lookup.schedule.enabled == enabled) {
            ScheduleWorkflowResult.Applied(
                operation = if (enabled) ScheduleOperation.ENABLED else ScheduleOperation.DISABLED,
                schedule = lookup.schedule,
            )
        } else {
            val operationName = if (enabled) "enable" else "disable"
            mutationGuard.pixelCameraOwnerBlock(scheduleId, operationName) ?: run {
                val now = clock.now()
                val candidate = lookup.schedule.copy(
                    enabled = enabled,
                    updatedAt = nextScheduleRevision(now, lookup.schedule.updatedAt),
                )
                readiness.failure(candidate, now) ?: withContext(NonCancellable) {
                    transaction.replace(
                        previous = lookup.schedule,
                        candidate = candidate,
                        operation = if (enabled) ScheduleOperation.ENABLED else ScheduleOperation.DISABLED,
                    )
                }
            }
        }
    }

    private suspend fun deleteLocked(scheduleId: ScheduleId): ScheduleWorkflowResult =
        when (val lookup = mutationGuard.load(scheduleId)) {
            is ScheduleLookup.Failed -> lookup.result
            is ScheduleLookup.Found -> mutationGuard.pixelCameraOwnerBlock(scheduleId, "delete")
                ?: withContext(NonCancellable) { transaction.delete(lookup.schedule) }
        }
}
