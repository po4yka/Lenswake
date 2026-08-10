package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationProfileRepository
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.LenswakeClock
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import dev.po4yka.lenswake.core.ScheduleValidation
import dev.po4yka.lenswake.core.ScheduleValidationError
import dev.po4yka.lenswake.core.ScheduleValidator
import dev.po4yka.lenswake.core.TimeLapseSpeed
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScheduleCommand(
    val name: String,
    val startAt: Instant,
    val stopAt: Instant,
    val zoneId: ZoneId,
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
    private val scheduleRepository: ScheduleRepository,
    private val executionRepository: ExecutionRepository,
    private val profileRepository: AutomationProfileRepository,
    private val scheduler: RecordingScheduler,
    private val clock: LenswakeClock,
    private val preflightProbe: RuntimePreflightProbe,
    private val validator: ScheduleValidator = ScheduleValidator(),
    private val mutationMutex: Mutex = Mutex(),
) {
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
            capture = supportedCapture,
            profileId = command.profileId,
            enabled = command.enabled,
            createdAt = now,
            updatedAt = now,
        )
        validate(schedule, now)?.let { return it }
        ensureProfileReady(schedule.profileId, requireRuntimeReady = schedule.enabled)?.let { return it }

        return withContext(NonCancellable) {
            replace(previous = null, candidate = schedule, operation = ScheduleOperation.CREATED)
        }
    }

    private suspend fun editLocked(
        scheduleId: ScheduleId,
        command: ScheduleCommand,
    ): ScheduleWorkflowResult {
        val previous = try {
            scheduleRepository.get(scheduleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return loadFailed(scheduleId, failure)
        } ?: return notFound(scheduleId)
        pixelCameraOwnerBlock(scheduleId, "edit")?.let { return it }
        val now = clock.now()
        val candidate = previous.copy(
            name = command.name.trim(),
            startAt = command.startAt,
            stopAt = command.stopAt,
            zoneId = command.zoneId,
            capture = supportedCapture,
            profileId = command.profileId,
            enabled = command.enabled,
            updatedAt = nextRevision(now, previous.updatedAt),
        )
        validate(candidate, now)?.let { return it }
        ensureProfileReady(candidate.profileId, requireRuntimeReady = candidate.enabled)?.let { return it }

        return withContext(NonCancellable) {
            replace(previous, candidate, ScheduleOperation.UPDATED)
        }
    }

    private suspend fun setEnabledLocked(
        scheduleId: ScheduleId,
        enabled: Boolean,
    ): ScheduleWorkflowResult {
        val previous = try {
            scheduleRepository.get(scheduleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return loadFailed(scheduleId, failure)
        } ?: return notFound(scheduleId)
        if (previous.enabled == enabled) {
            return ScheduleWorkflowResult.Applied(
                operation = if (enabled) ScheduleOperation.ENABLED else ScheduleOperation.DISABLED,
                schedule = previous,
            )
        }
        pixelCameraOwnerBlock(scheduleId, "disable")?.let { return it }
        val now = clock.now()
        val candidate = previous.copy(
            enabled = enabled,
            updatedAt = nextRevision(now, previous.updatedAt),
        )
        validate(candidate, now)?.let { return it }
        if (enabled) ensureProfileReady(candidate.profileId, requireRuntimeReady = true)?.let { return it }

        return withContext(NonCancellable) {
            replace(
                previous = previous,
                candidate = candidate,
                operation = if (enabled) ScheduleOperation.ENABLED else ScheduleOperation.DISABLED,
            )
        }
    }

    private suspend fun deleteLocked(scheduleId: ScheduleId): ScheduleWorkflowResult {
        val previous = try {
            scheduleRepository.get(scheduleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return loadFailed(scheduleId, failure)
        } ?: return notFound(scheduleId)
        pixelCameraOwnerBlock(scheduleId, "delete")?.let { return it }

        return withContext(NonCancellable) { deletePersisted(previous) }
    }

    private suspend fun deletePersisted(previous: RecordingSchedule): ScheduleWorkflowResult {
        persistFailClosedBeforeAlarmMutation(previous)?.let { return it }
        val cancelFailure = scheduler.cancel(previous.id).exceptionOrNull()
        if (cancelFailure != null) {
            val rollbackFailures = restore(previous)
            return ScheduleWorkflowResult.Failed(
                code = ScheduleWorkflowFailureCode.CANCEL_FAILED,
                message = "Could not cancel the schedule alarms before deletion: ${cancelFailure.safeMessage()}.",
                rollbackFailures = rollbackFailures,
            )
        }

        return try {
            scheduleRepository.delete(previous.id)
            ScheduleWorkflowResult.Deleted(previous.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ScheduleWorkflowResult.Failed(
                code = ScheduleWorkflowFailureCode.DELETE_FAILED,
                message = "Could not delete the persisted schedule: ${failure.safeMessage()}.",
                rollbackFailures = restore(previous),
            )
        }
    }

    private suspend fun replace(
        previous: RecordingSchedule?,
        candidate: RecordingSchedule,
        operation: ScheduleOperation,
    ): ScheduleWorkflowResult {
        if (previous != null) {
            persistFailClosedBeforeAlarmMutation(previous)?.let { return it }
            val cancelFailure = scheduler.cancel(previous.id).exceptionOrNull()
            if (cancelFailure != null) {
                return ScheduleWorkflowResult.Failed(
                    code = ScheduleWorkflowFailureCode.CANCEL_FAILED,
                    message = "Could not cancel the previous schedule alarms: ${cancelFailure.safeMessage()}.",
                    rollbackFailures = restore(previous),
                )
            }
        }

        val persistedCandidate = if (candidate.enabled) candidate.copy(enabled = false) else candidate
        try {
            scheduleRepository.save(persistedCandidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ScheduleWorkflowResult.Failed(
                code = ScheduleWorkflowFailureCode.PERSIST_FAILED,
                message = "Could not persist the schedule: ${failure.safeMessage()}.",
                rollbackFailures = previous?.let { restore(it) }.orEmpty(),
            )
        }

        if (!candidate.enabled) {
            return ScheduleWorkflowResult.Applied(operation, candidate)
        }

        scheduler.scheduleStop(candidate).exceptionOrNull()?.let { failure ->
            return failedRegistration(
                previous = previous,
                candidate = candidate,
                code = ScheduleWorkflowFailureCode.STOP_ALARM_FAILED,
                message = "The exact STOP alarm was not registered: ${failure.safeMessage()}.",
            )
        }
        scheduler.scheduleStart(candidate).exceptionOrNull()?.let { failure ->
            return failedRegistration(
                previous = previous,
                candidate = candidate,
                code = ScheduleWorkflowFailureCode.START_ALARM_FAILED,
                message = "The exact START alarm was not registered: ${failure.safeMessage()}.",
            )
        }

        try {
            scheduleRepository.save(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failedRegistration(
                previous = previous,
                candidate = candidate,
                code = ScheduleWorkflowFailureCode.PERSIST_FAILED,
                message = "The schedule alarms were registered, but activation could not be persisted: " +
                    "${failure.safeMessage()}.",
            )
        }

        return ScheduleWorkflowResult.Applied(operation, candidate)
    }

    private suspend fun persistFailClosedBeforeAlarmMutation(
        schedule: RecordingSchedule,
    ): ScheduleWorkflowResult.Failed? {
        if (!schedule.enabled) return null
        return try {
            scheduleRepository.save(schedule.copy(enabled = false))
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ScheduleWorkflowResult.Failed(
                code = ScheduleWorkflowFailureCode.PERSIST_FAILED,
                message = "Could not persist a fail-closed schedule state before changing alarms: " +
                    "${failure.safeMessage()}.",
            )
        }
    }

    private suspend fun failedRegistration(
        previous: RecordingSchedule?,
        candidate: RecordingSchedule,
        code: ScheduleWorkflowFailureCode,
        message: String,
    ): ScheduleWorkflowResult.Failed {
        val rollbackFailures = buildList {
            scheduler.cancel(candidate.id).exceptionOrNull()?.let {
                add("Could not cancel the partially registered alarms: ${it.safeMessage()}.")
            }
            if (previous == null) {
                try {
                    scheduleRepository.delete(candidate.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    add("Could not remove the rolled-back schedule: ${failure.safeMessage()}.")
                }
            } else {
                addAll(restore(previous, cancelFirst = false))
            }
        }
        return ScheduleWorkflowResult.Failed(code, message, rollbackFailures)
    }

    private suspend fun restore(
        schedule: RecordingSchedule,
        cancelFirst: Boolean = true,
    ): List<String> = buildList {
        if (cancelFirst) {
            scheduler.cancel(schedule.id).exceptionOrNull()?.let {
                add("Could not clear alarm state during rollback: ${it.safeMessage()}.")
            }
        }
        val failClosedSchedule = if (schedule.enabled) schedule.copy(enabled = false) else schedule
        val persisted = try {
            scheduleRepository.save(failClosedSchedule)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            add("Could not restore the persisted schedule: ${failure.safeMessage()}.")
            false
        }
        if (persisted && schedule.enabled) {
            val stopFailure = scheduler.scheduleStop(schedule).exceptionOrNull()
            stopFailure?.let {
                add("Could not restore the previous STOP alarm: ${it.safeMessage()}.")
            }
            if (stopFailure == null) {
                val startFailure = scheduler.scheduleStart(schedule).exceptionOrNull()
                startFailure?.let {
                    add("Could not restore the previous START alarm: ${it.safeMessage()}.")
                }
                if (startFailure == null) {
                    try {
                        scheduleRepository.save(schedule)
                        return@buildList
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        add("Could not reactivate the previous schedule after restoring its alarms: ${failure.safeMessage()}.")
                    }
                }
            }
            scheduler.cancel(schedule.id).exceptionOrNull()?.let {
                add("Could not cancel alarms after incomplete rollback restoration: ${it.safeMessage()}.")
            }
        }
    }

    private suspend fun validate(
        schedule: RecordingSchedule,
        now: Instant,
    ): ScheduleWorkflowResult.Rejected? {
        val validation = if (schedule.enabled) {
            validator.validateForScheduling(schedule, now)
        } else {
            validator.validateForPersistence(schedule)
        }
        return when (validation) {
            ScheduleValidation.Valid -> null
            is ScheduleValidation.Invalid -> ScheduleWorkflowResult.Rejected(
                code = ScheduleWorkflowFailureCode.INVALID_SCHEDULE,
                message = validation.errors.joinToString(", ") { it.message },
                validationErrors = validation.errors,
            )
        }
    }

    private suspend fun ensureProfileReady(
        profileId: ProfileId,
        requireRuntimeReady: Boolean,
    ): ScheduleWorkflowResult? = try {
        when (val profile = profileRepository.get(profileId)) {
            null -> ScheduleWorkflowResult.Rejected(
                code = ScheduleWorkflowFailureCode.PROFILE_NOT_FOUND,
                message = "The selected Pixel Camera profile is not installed.",
            )
            else -> if (profile.compatibility != ProfileCompatibility.VERIFIED || profile.verifiedAt == null) {
                ScheduleWorkflowResult.Rejected(
                    code = ScheduleWorkflowFailureCode.PROFILE_NOT_VERIFIED,
                    message = "The selected Pixel Camera profile has not passed a production rehearsal.",
                )
            } else if (!requireRuntimeReady) {
                null
            } else {
                val preflight = preflightProbe.inspect(listOf(profile))
                if (preflight.hasAllRequiredChecksPassed()) {
                    null
                } else {
                    ScheduleWorkflowResult.Rejected(
                        code = ScheduleWorkflowFailureCode.RUNTIME_NOT_READY,
                        message = requiredPreflightChecks.mapNotNull { required ->
                            val check = preflight.checks.singleOrNull { it.type == required }
                            if (
                                check != null &&
                                (
                                    check.severity != dev.po4yka.lenswake.core.PreflightSeverity.BLOCKING ||
                                        check.status == dev.po4yka.lenswake.core.PreflightStatus.PASSED
                                )
                            ) {
                                null
                            } else {
                                check?.message ?: "$required was not reported."
                            }
                        }.joinToString(" "),
                    )
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ScheduleWorkflowResult.Failed(
            code = ScheduleWorkflowFailureCode.PREFLIGHT_FAILED,
            message = "Current schedule readiness could not be verified: ${failure.safeMessage()}.",
        )
    }

    private fun notFound(scheduleId: ScheduleId) = ScheduleWorkflowResult.Rejected(
        code = ScheduleWorkflowFailureCode.SCHEDULE_NOT_FOUND,
        message = "Schedule ${scheduleId.value} no longer exists.",
    )

    private fun loadFailed(
        scheduleId: ScheduleId,
        failure: Exception,
    ) = ScheduleWorkflowResult.Failed(
        code = ScheduleWorkflowFailureCode.PERSIST_FAILED,
        message = "Schedule ${scheduleId.value} could not be loaded: ${failure.safeMessage()}.",
    )

    private suspend fun pixelCameraOwnerBlock(
        scheduleId: ScheduleId,
        operation: String,
    ): ScheduleWorkflowResult? = try {
        val owner = executionRepository.findPixelCameraOwnerForSchedule(scheduleId)
        if (owner == null) {
            null
        } else {
            ScheduleWorkflowResult.Rejected(
                code = ScheduleWorkflowFailureCode.SCHEDULE_EXECUTION_ACTIVE,
                message = "Schedule ${scheduleId.value} cannot be $operation while execution " +
                    "${owner.id.value} owns or may still own Pixel Camera.",
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ScheduleWorkflowResult.Failed(
            code = ScheduleWorkflowFailureCode.EXECUTION_STATE_UNAVAILABLE,
            message = "Pixel Camera ownership for schedule ${scheduleId.value} could not be verified: " +
                "${failure.safeMessage()}.",
        )
    }

    private fun nextRevision(now: Instant, previous: Instant): Instant =
        if (now.toEpochMilli() > previous.toEpochMilli()) now else previous.plusMillis(1)

    private fun Throwable.safeMessage(): String = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

    private fun dev.po4yka.lenswake.core.PreflightReport.hasAllRequiredChecksPassed(): Boolean =
        requiredPreflightChecks.all { required ->
            checks.singleOrNull { it.type == required }?.let { check ->
                check.severity != dev.po4yka.lenswake.core.PreflightSeverity.BLOCKING ||
                    check.status == dev.po4yka.lenswake.core.PreflightStatus.PASSED
            } == true
        }

    private val ScheduleValidationError.message: String
        get() = when (this) {
            ScheduleValidationError.BLANK_NAME -> "Name is required"
            ScheduleValidationError.STOP_NOT_AFTER_START -> "Stop time must be after start time"
            ScheduleValidationError.UPDATED_BEFORE_CREATED -> "Schedule revision is invalid"
            ScheduleValidationError.START_NOT_IN_FUTURE -> "Start time must be in the future"
            ScheduleValidationError.SCHEDULE_DISABLED -> "Schedule is disabled"
        }

    private companion object {
        val supportedCapture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X120,
            lens = LensSelection.REAR_MAIN,
        )
        val requiredPreflightChecks = setOf(
            dev.po4yka.lenswake.core.PreflightCheckType.EXACT_ALARMS,
            dev.po4yka.lenswake.core.PreflightCheckType.NOTIFICATIONS,
            dev.po4yka.lenswake.core.PreflightCheckType.FULL_SCREEN_INTENT,
            dev.po4yka.lenswake.core.PreflightCheckType.PIXEL_CAMERA_INSTALLED,
            dev.po4yka.lenswake.core.PreflightCheckType.SECURE_CAMERA_RESOLVES,
            dev.po4yka.lenswake.core.PreflightCheckType.DEVICE_WAKE,
            dev.po4yka.lenswake.core.PreflightCheckType.ACCESSIBILITY_ENABLED,
            dev.po4yka.lenswake.core.PreflightCheckType.ACCESSIBILITY_CONNECTED,
            dev.po4yka.lenswake.core.PreflightCheckType.PROFILE_AVAILABLE,
            dev.po4yka.lenswake.core.PreflightCheckType.PROFILE_COMPATIBILITY,
            dev.po4yka.lenswake.core.PreflightCheckType.REHEARSAL_CURRENT,
            dev.po4yka.lenswake.core.PreflightCheckType.BATTERY,
            dev.po4yka.lenswake.core.PreflightCheckType.CHARGING,
            dev.po4yka.lenswake.core.PreflightCheckType.STORAGE,
        )
    }
}
