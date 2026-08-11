package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.ExecutionRepository
import dev.po4yka.lenswake.core.RecordingSchedule
import dev.po4yka.lenswake.core.ScheduleId
import dev.po4yka.lenswake.core.ScheduleRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal sealed interface ScheduleLookup {
    data class Found(
        val schedule: RecordingSchedule,
    ) : ScheduleLookup

    data class Failed(
        val result: ScheduleWorkflowResult,
    ) : ScheduleLookup
}

internal class ScheduleMutationGuard(
    private val scheduleRepository: ScheduleRepository,
    private val executionRepository: ExecutionRepository,
) {
    suspend fun load(scheduleId: ScheduleId): ScheduleLookup =
        captureScheduleFailure { scheduleRepository.get(scheduleId) }.fold(
            onSuccess = { schedule ->
                schedule?.let(ScheduleLookup::Found) ?: ScheduleLookup.Failed(
                    ScheduleWorkflowResult.Rejected(
                        code = ScheduleWorkflowFailureCode.SCHEDULE_NOT_FOUND,
                        message = "Schedule ${scheduleId.value} no longer exists.",
                    ),
                )
            },
            onFailure = { failure ->
                ScheduleLookup.Failed(
                    ScheduleWorkflowResult.Failed(
                        code = ScheduleWorkflowFailureCode.PERSIST_FAILED,
                        message = "Schedule ${scheduleId.value} could not be loaded: ${failure.safeMessage()}.",
                    ),
                )
            },
        )

    suspend fun pixelCameraOwnerBlock(
        scheduleId: ScheduleId,
        operation: String,
    ): ScheduleWorkflowResult? =
        captureScheduleFailure {
            executionRepository.findPixelCameraOwnerForSchedule(scheduleId)
        }.fold(
            onSuccess = { owner ->
                owner?.let {
                    ScheduleWorkflowResult.Rejected(
                        code = ScheduleWorkflowFailureCode.SCHEDULE_EXECUTION_ACTIVE,
                        message = "Schedule ${scheduleId.value} cannot be $operation while execution " +
                            "${owner.id.value} owns or may still own Pixel Camera.",
                    )
                }
            },
            onFailure = { failure ->
                ScheduleWorkflowResult.Failed(
                    code = ScheduleWorkflowFailureCode.EXECUTION_STATE_UNAVAILABLE,
                    message = "Pixel Camera ownership for schedule ${scheduleId.value} could not be verified: " +
                        "${failure.safeMessage()}.",
                )
            },
        )
}

internal suspend inline fun <T> captureScheduleFailure(
    crossinline block: suspend () -> T,
): Result<T> = runCatching { block() }.onFailure { failure ->
    if (failure is CancellationException || failure !is Exception) throw failure
}

internal fun nextScheduleRevision(now: Instant, previous: Instant): Instant =
    if (now.toEpochMilli() > previous.toEpochMilli()) now else previous.plusMillis(1)

internal fun Throwable.safeMessage(): String =
    message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
