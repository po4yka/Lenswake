package dev.po4yka.lenswake.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleFormTest {
    @Test
    fun validGuidedValuesCanBeSubmitted() {
        val validation = validForm().validateForDisplay(listOf(verifiedProfile), now, TestUiStringProvider)

        assertTrue(validation.canSubmit)
        assertNull(validation.nameError)
        assertNull(validation.timingError)
        assertNull(validation.profileError)
    }

    @Test
    fun blankNameIsExplainedBeforeSubmission() {
        val validation = validForm().copy(name = " ")
            .validateForDisplay(listOf(verifiedProfile), now, TestUiStringProvider)

        assertFalse(validation.canSubmit)
        assertEquals("Add a name so you can recognize this schedule.", validation.nameError)
    }

    @Test
    fun endBeforeStartIsExplainedBeforeSubmission() {
        val validation = validForm()
            .copy(stopLocal = LocalDateTime.of(2030, 1, 1, 5, 59))
            .validateForDisplay(listOf(verifiedProfile), now, TestUiStringProvider)

        assertFalse(validation.canSubmit)
        assertEquals("End must be after start.", validation.timingError)
    }

    @Test
    fun daylightSavingGapCannotBeSubmitted() {
        val validation = validForm()
            .copy(
                startLocal = LocalDateTime.of(2030, 3, 10, 2, 30),
                stopLocal = LocalDateTime.of(2030, 3, 10, 4, 0),
                zoneId = ZoneId.of("America/New_York"),
            )
            .validateForDisplay(listOf(verifiedProfile), now, TestUiStringProvider)

        assertFalse(validation.canSubmit)
        assertEquals(
            "Choose a different time; this time is affected by a daylight-saving clock change.",
            validation.timingError,
        )
    }

    @Test
    fun activeScheduleCannotStartInThePast() {
        val validation = validForm()
            .copy(
                startLocal = LocalDateTime.of(2029, 12, 31, 23, 0),
                stopLocal = LocalDateTime.of(2030, 1, 1, 1, 0),
            )
            .validateForDisplay(listOf(verifiedProfile), now, TestUiStringProvider)

        assertFalse(validation.canSubmit)
        assertEquals("Start must be in the future while the schedule is active.", validation.timingError)
    }

    @Test
    fun defaultStartHasLeadTimeAndUsesTheNextQuarterHour() {
        assertEquals(
            LocalDateTime.of(2030, 1, 1, 10, 30),
            defaultScheduleStart(
                now = Instant.parse("2030-01-01T10:02:30Z"),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    private fun validForm() = ScheduleFormUiState(
        name = "Dawn",
        startLocal = LocalDateTime.of(2030, 1, 1, 6, 0),
        stopLocal = LocalDateTime.of(2030, 1, 1, 8, 0),
        zoneId = ZoneId.of("Asia/Tbilisi"),
        profileId = verifiedProfile.id,
    )

    private companion object {
        val now: Instant = Instant.parse("2030-01-01T00:00:00Z")
        val verifiedProfile = ProfileSummaryUiState(
            id = "profile-verified",
            title = "Pixel 8 Pro",
            environment = "Android 17",
            compatibility = "Verified",
            verifiedForScheduling = true,
        )
    }
}
