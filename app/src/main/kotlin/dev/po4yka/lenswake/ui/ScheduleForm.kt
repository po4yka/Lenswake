package dev.po4yka.lenswake.ui

import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.CaptureMode

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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
    strings: UiStringProvider,
): ScheduleFormValidation {
    val nameError = if (name.isBlank()) {
        strings.get(dev.po4yka.lenswake.R.string.validation_schedule_name_required)
    } else {
        null
    }
    val startInstant = startLocal?.toUnambiguousInstantOrNull(zoneId)
    val stopInstant = stopLocal?.toUnambiguousInstantOrNull(zoneId)
    val timingError = when {
        startLocal == null || stopLocal == null ->
            strings.get(dev.po4yka.lenswake.R.string.validation_schedule_times_required)
        startInstant == null || stopInstant == null ->
            strings.get(dev.po4yka.lenswake.R.string.validation_schedule_dst_gap)
        !stopLocal.isAfter(startLocal) ->
            strings.get(dev.po4yka.lenswake.R.string.validation_schedule_end_after_start)
        enabled && !startInstant.isAfter(now) ->
            strings.get(dev.po4yka.lenswake.R.string.validation_schedule_start_future)
        else -> null
    }
    val selectedProfile = profiles.singleOrNull { it.id == profileId && it.verifiedForScheduling }
    val profileError = if (selectedProfile != null) {
        null
    } else {
        strings.get(dev.po4yka.lenswake.R.string.validation_schedule_profile_required)
    }
    val captureError = selectedProfile?.takeUnless { captureConfiguration() in it.supportedCaptures }?.let {
        strings.get(dev.po4yka.lenswake.R.string.validation_schedule_capture_unsupported)
    }
    return ScheduleFormValidation(
        nameError = nameError,
        timingError = timingError,
        profileError = profileError,
        captureError = captureError,
    )
}

internal fun ScheduleFormUiState.captureConfiguration(): CaptureConfiguration = when (captureMode) {
    CaptureMode.VIDEO -> CaptureConfiguration.Video(lens)
    CaptureMode.TIME_LAPSE -> CaptureConfiguration.TimeLapse(timeLapseSpeed, lens)
    CaptureMode.NIGHT_SIGHT_TIME_LAPSE -> CaptureConfiguration.NightSightTimeLapse(lens)
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
