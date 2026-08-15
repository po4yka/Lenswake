package dev.po4yka.lenswake.core

import java.time.Instant
import java.time.ZoneId

data class RecordingSchedule(
    val id: ScheduleId,
    val name: String,
    val startAt: Instant,
    val stopAt: Instant,
    val zoneId: ZoneId,
    val capture: CaptureConfiguration,
    val profileId: ProfileId,
    val profileProvenance: ProfileProvenance = LEGACY_PROFILE_PROVENANCE,
    val experimentalRiskAccepted: Boolean = false,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class ScheduleValidationError {
    BLANK_NAME,
    STOP_NOT_AFTER_START,
    UPDATED_BEFORE_CREATED,
    START_NOT_IN_FUTURE,
    SCHEDULE_DISABLED,
}

sealed interface ScheduleValidation {
    data object Valid : ScheduleValidation

    data class Invalid(
        val errors: Set<ScheduleValidationError>,
    ) : ScheduleValidation {
        init {
            require(errors.isNotEmpty()) { "Invalid schedule must have at least one error" }
        }
    }
}

class ScheduleValidator {
    fun validateForPersistence(schedule: RecordingSchedule): ScheduleValidation = validationOf(
        buildSet {
            if (schedule.name.isBlank()) add(ScheduleValidationError.BLANK_NAME)
            if (!schedule.stopAt.isAfter(schedule.startAt)) {
                add(ScheduleValidationError.STOP_NOT_AFTER_START)
            }
            if (schedule.updatedAt.isBefore(schedule.createdAt)) {
                add(ScheduleValidationError.UPDATED_BEFORE_CREATED)
            }
        },
    )

    fun validateForScheduling(
        schedule: RecordingSchedule,
        now: Instant,
    ): ScheduleValidation {
        val persistenceErrors = when (val validation = validateForPersistence(schedule)) {
            ScheduleValidation.Valid -> emptySet()
            is ScheduleValidation.Invalid -> validation.errors
        }
        return validationOf(
            buildSet {
                addAll(persistenceErrors)
                if (!schedule.enabled) add(ScheduleValidationError.SCHEDULE_DISABLED)
                if (!schedule.startAt.isAfter(now)) add(ScheduleValidationError.START_NOT_IN_FUTURE)
            },
        )
    }

    private fun validationOf(errors: Set<ScheduleValidationError>): ScheduleValidation =
        if (errors.isEmpty()) ScheduleValidation.Valid else ScheduleValidation.Invalid(errors)
}
