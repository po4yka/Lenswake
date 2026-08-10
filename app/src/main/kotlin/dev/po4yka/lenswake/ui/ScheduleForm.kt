package dev.po4yka.lenswake.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal data class ScheduleFormValidation(
    val nameError: String? = null,
    val timingError: String? = null,
    val profileError: String? = null,
) {
    val canSubmit: Boolean
        get() = nameError == null && timingError == null && profileError == null
}

internal fun defaultScheduleStart(
    now: Instant,
    zoneId: ZoneId,
): LocalDateTime {
    val candidate = now.atZone(zoneId)
        .toLocalDateTime()
        .plusMinutes(DEFAULT_START_LEAD_MINUTES)
        .withSecond(0)
        .withNano(0)
    val minutesToQuarterHour = (MINUTES_PER_QUARTER_HOUR - candidate.minute % MINUTES_PER_QUARTER_HOUR) %
        MINUTES_PER_QUARTER_HOUR
    return candidate.plusMinutes(minutesToQuarterHour.toLong())
}

internal fun ScheduleFormUiState.validateForDisplay(
    profiles: List<ProfileSummaryUiState>,
    now: Instant = Instant.now(),
): ScheduleFormValidation {
    val nameError = if (name.isBlank()) {
        "Add a name so you can recognize this schedule."
    } else {
        null
    }
    val startInstant = startLocal?.toUnambiguousInstantOrNull(zoneId)
    val stopInstant = stopLocal?.toUnambiguousInstantOrNull(zoneId)
    val timingError = when {
        startLocal == null || stopLocal == null -> "Choose a start and end date and time."
        startInstant == null || stopInstant == null ->
            "Choose a different time; this time is affected by a daylight-saving clock change."
        !stopLocal.isAfter(startLocal) -> "End must be after start."
        enabled && !startInstant.isAfter(now) -> "Start must be in the future while the schedule is active."
        else -> null
    }
    val profileError = if (profiles.any { it.id == profileId && it.verifiedForScheduling }) {
        null
    } else {
        "Choose a camera setup verified for scheduling."
    }
    return ScheduleFormValidation(
        nameError = nameError,
        timingError = timingError,
        profileError = profileError,
    )
}

internal fun ScheduleFormUiState.withStartDate(date: LocalDate): ScheduleFormUiState = copy(
    startLocal = date.atTime(startLocal?.toLocalTime() ?: LocalTime.NOON),
)

internal fun ScheduleFormUiState.withStartTime(time: LocalTime): ScheduleFormUiState = copy(
    startLocal = (startLocal?.toLocalDate() ?: LocalDate.now(zoneId)).atTime(time),
)

internal fun ScheduleFormUiState.withStopDate(date: LocalDate): ScheduleFormUiState = copy(
    stopLocal = date.atTime(stopLocal?.toLocalTime() ?: LocalTime.NOON),
)

internal fun ScheduleFormUiState.withStopTime(time: LocalTime): ScheduleFormUiState = copy(
    stopLocal = (stopLocal?.toLocalDate() ?: LocalDate.now(zoneId)).atTime(time),
)

private fun LocalDateTime.toUnambiguousInstantOrNull(zoneId: ZoneId): Instant? {
    val offsets = zoneId.rules.getValidOffsets(this)
    return offsets.singleOrNull()?.let(::toInstant)
}

private const val DEFAULT_START_LEAD_MINUTES = 15L
private const val MINUTES_PER_QUARTER_HOUR = 15
