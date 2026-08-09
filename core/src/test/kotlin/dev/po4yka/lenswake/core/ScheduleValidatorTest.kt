package dev.po4yka.lenswake.core

import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ScheduleValidatorTest {
    private val validator = ScheduleValidator()
    private val base = RecordingSchedule(
        id = ScheduleId.new(),
        name = "Sunrise",
        startAt = Instant.parse("2026-08-10T01:30:00Z"),
        stopAt = Instant.parse("2026-08-10T03:30:00Z"),
        zoneId = ZoneId.of("Asia/Tbilisi"),
        capture = CaptureConfiguration.TimeLapse(
            speed = TimeLapseSpeed.X120,
            lens = LensSelection.REAR_MAIN,
            zoom = Zoom.of(1f),
        ),
        profileId = ProfileId.new(),
        enabled = true,
        createdAt = Instant.parse("2026-08-09T10:00:00Z"),
        updatedAt = Instant.parse("2026-08-09T10:00:00Z"),
    )

    @Test
    fun `valid future time lapse schedule is accepted`() {
        assertEquals(
            ScheduleValidation.Valid,
            validator.validateForScheduling(base, Instant.parse("2026-08-09T12:00:00Z")),
        )
    }

    @Test
    fun `persistence validation reports all corrupt persisted fields`() {
        val result = validator.validateForPersistence(
            base.copy(
                name = " ",
                startAt = base.stopAt,
                updatedAt = base.createdAt.minusSeconds(1),
            ),
        )

        val invalid = assertInstanceOf(ScheduleValidation.Invalid::class.java, result)
        assertEquals(
            setOf(
                ScheduleValidationError.BLANK_NAME,
                ScheduleValidationError.STOP_NOT_AFTER_START,
                ScheduleValidationError.UPDATED_BEFORE_CREATED,
            ),
            invalid.errors,
        )
    }

    @Test
    fun `scheduling validation rejects disabled or non-future schedules`() {
        val result = validator.validateForScheduling(
            base.copy(enabled = false),
            now = base.startAt,
        )

        val invalid = assertInstanceOf(ScheduleValidation.Invalid::class.java, result)
        assertEquals(
            setOf(
                ScheduleValidationError.SCHEDULE_DISABLED,
                ScheduleValidationError.START_NOT_IN_FUTURE,
            ),
            invalid.errors,
        )
    }
}
