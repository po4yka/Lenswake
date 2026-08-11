package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.RecordingScheduler
import dev.po4yka.lenswake.core.ScheduleRepository

internal class ScheduleMutationTransaction(
    private val scheduleRepository: ScheduleRepository,
    private val scheduler: RecordingScheduler,
) {
    suspend fun delete(previous: RecordingSchedule): ScheduleWorkflowResult =
        persistFailClosedBeforeAlarmMutation(previous) ?: cancelThenDelete(previous)

    suspend fun replace(
        previous: RecordingSchedule?,
        candidate: RecordingSchedule,
        operation: ScheduleOperation,
    ): ScheduleWorkflowResult = previousMutationFailure(previous)
        ?: persistCandidate(previous, candidate, operation)

    private suspend fun cancelThenDelete(previous: RecordingSchedule): ScheduleWorkflowResult {
        val cancelFailure = scheduler.cancel(previous.id).exceptionOrNull()
        return if (cancelFailure != null) {
            ScheduleWorkflowResult.Failed(
                code = ScheduleWorkflowFailureCode.CANCEL_FAILED,
                message = "Could not cancel the schedule alarms before deletion: ${cancelFailure.safeMessage()}.",
                rollbackFailures = restore(previous),
            )
        } else {
            captureScheduleFailure { scheduleRepository.delete(previous.id) }.fold(
                onSuccess = { ScheduleWorkflowResult.Deleted(previous.id) },
                onFailure = { failure ->
                    ScheduleWorkflowResult.Failed(
                        code = ScheduleWorkflowFailureCode.DELETE_FAILED,
                        message = "Could not delete the persisted schedule: ${failure.safeMessage()}.",
                        rollbackFailures = restore(previous),
                    )
                },
            )
        }
    }

    private suspend fun previousMutationFailure(
        previous: RecordingSchedule?,
    ): ScheduleWorkflowResult.Failed? {
        if (previous == null) return null
        val persistenceFailure = persistFailClosedBeforeAlarmMutation(previous)
        val cancelFailure = if (persistenceFailure == null) {
            scheduler.cancel(previous.id).exceptionOrNull()
        } else {
            null
        }
        return persistenceFailure ?: cancelFailure?.let { failure ->
            ScheduleWorkflowResult.Failed(
                code = ScheduleWorkflowFailureCode.CANCEL_FAILED,
                message = "Could not cancel the previous schedule alarms: ${failure.safeMessage()}.",
                rollbackFailures = restore(previous),
            )
        }
    }

    private suspend fun persistCandidate(
        previous: RecordingSchedule?,
        candidate: RecordingSchedule,
        operation: ScheduleOperation,
    ): ScheduleWorkflowResult {
        val failClosedCandidate = if (candidate.enabled) candidate.copy(enabled = false) else candidate
        return captureScheduleFailure { scheduleRepository.save(failClosedCandidate) }.fold(
            onSuccess = {
                if (candidate.enabled) {
                    activateCandidate(previous, candidate, operation)
                } else {
                    ScheduleWorkflowResult.Applied(operation, candidate)
                }
            },
            onFailure = { failure ->
                ScheduleWorkflowResult.Failed(
                    code = ScheduleWorkflowFailureCode.PERSIST_FAILED,
                    message = "Could not persist the schedule: ${failure.safeMessage()}.",
                    rollbackFailures = previous?.let { restore(it) }.orEmpty(),
                )
            },
        )
    }

    private suspend fun activateCandidate(
        previous: RecordingSchedule?,
        candidate: RecordingSchedule,
        operation: ScheduleOperation,
    ): ScheduleWorkflowResult {
        val stopFailure = scheduler.stageStop(candidate).exceptionOrNull()
        val startFailure = if (stopFailure == null) {
            scheduler.stageStart(candidate).exceptionOrNull()
        } else {
            null
        }
        return when {
            stopFailure != null -> failedRegistration(
                previous = previous,
                candidate = candidate,
                code = ScheduleWorkflowFailureCode.STOP_ALARM_FAILED,
                message = "The exact STOP alarm was not registered: ${stopFailure.safeMessage()}.",
            )
            startFailure != null -> failedRegistration(
                previous = previous,
                candidate = candidate,
                code = ScheduleWorkflowFailureCode.START_ALARM_FAILED,
                message = "The exact START alarm was not registered: ${startFailure.safeMessage()}.",
            )
            else -> persistActivatedCandidate(previous, candidate, operation)
        }
    }

    private suspend fun persistActivatedCandidate(
        previous: RecordingSchedule?,
        candidate: RecordingSchedule,
        operation: ScheduleOperation,
    ): ScheduleWorkflowResult = captureScheduleFailure {
        scheduleRepository.save(candidate)
    }.fold(
        onSuccess = { ScheduleWorkflowResult.Applied(operation, candidate) },
        onFailure = { failure ->
            failedRegistration(
                previous = previous,
                candidate = candidate,
                code = ScheduleWorkflowFailureCode.PERSIST_FAILED,
                message = "The schedule alarms were registered, but activation could not be persisted: " +
                    "${failure.safeMessage()}.",
            )
        },
    )

    private suspend fun persistFailClosedBeforeAlarmMutation(
        schedule: RecordingSchedule,
    ): ScheduleWorkflowResult.Failed? = if (!schedule.enabled) {
        null
    } else {
        captureScheduleFailure { scheduleRepository.save(schedule.copy(enabled = false)) }.fold(
            onSuccess = { null },
            onFailure = { failure ->
                ScheduleWorkflowResult.Failed(
                    code = ScheduleWorkflowFailureCode.PERSIST_FAILED,
                    message = "Could not persist a fail-closed schedule state before changing alarms: " +
                        "${failure.safeMessage()}.",
                )
            },
        )
    }

    private suspend fun failedRegistration(
        previous: RecordingSchedule?,
        candidate: RecordingSchedule,
        code: ScheduleWorkflowFailureCode,
        message: String,
    ): ScheduleWorkflowResult.Failed {
        val rollbackFailures = buildList {
            scheduler.cancel(candidate.id).exceptionOrNull()?.let { failure ->
                add("Could not cancel the partially registered alarms: ${failure.safeMessage()}.")
            }
            if (previous == null) {
                captureScheduleFailure { scheduleRepository.delete(candidate.id) }.exceptionOrNull()?.let { failure ->
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
            scheduler.cancel(schedule.id).exceptionOrNull()?.let { failure ->
                add("Could not clear alarm state during rollback: ${failure.safeMessage()}.")
            }
        }
        val failClosedSchedule = if (schedule.enabled) schedule.copy(enabled = false) else schedule
        val persistenceFailure = captureScheduleFailure {
            scheduleRepository.save(failClosedSchedule)
        }.exceptionOrNull()
        if (persistenceFailure != null) {
            add("Could not restore the persisted schedule: ${persistenceFailure.safeMessage()}.")
        } else if (schedule.enabled) {
            addAll(restoreEnabledSchedule(schedule))
        }
    }

    private suspend fun restoreEnabledSchedule(schedule: RecordingSchedule): List<String> = buildList {
        val stopFailure = scheduler.stageStop(schedule).exceptionOrNull()
        stopFailure?.let { add("Could not restore the previous STOP alarm: ${it.safeMessage()}.") }
        val startFailure = if (stopFailure == null) {
            scheduler.stageStart(schedule).exceptionOrNull()
        } else {
            null
        }
        startFailure?.let { add("Could not restore the previous START alarm: ${it.safeMessage()}.") }
        if (stopFailure == null && startFailure == null) {
            captureScheduleFailure { scheduleRepository.save(schedule) }.exceptionOrNull()?.let { failure ->
                add("Could not reactivate the previous schedule after restoring its alarms: " +
                    "${failure.safeMessage()}.")
            }
        }
        if (isNotEmpty()) {
            scheduler.cancel(schedule.id).exceptionOrNull()?.let { failure ->
                add("Could not cancel alarms after incomplete rollback restoration: ${failure.safeMessage()}.")
            }
        }
    }
}
