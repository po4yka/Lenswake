package dev.po4yka.lenswake.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreflightReportTest {
    @Test
    fun `passed checks are ready`() {
        val report = PreflightReport(
            listOf(check(PreflightSeverity.BLOCKING, PreflightStatus.PASSED)),
        )

        assertTrue(report.ready)
        assertInstanceOf(ScheduleReadiness.Ready::class.java, report.readiness)
    }

    @Test
    fun `non-passing warnings remain ready but visible`() {
        val warning = check(PreflightSeverity.WARNING, PreflightStatus.FAILED)
        val report = PreflightReport(listOf(warning))

        assertTrue(report.ready)
        assertInstanceOf(ScheduleReadiness.ReadyWithWarnings::class.java, report.readiness)
    }

    @Test
    fun `unknown blocking check fails closed`() {
        val report = PreflightReport(
            listOf(check(PreflightSeverity.BLOCKING, PreflightStatus.UNKNOWN)),
        )

        assertFalse(report.ready)
        assertInstanceOf(ScheduleReadiness.Blocked::class.java, report.readiness)
    }

    private fun check(severity: PreflightSeverity, status: PreflightStatus) = PreflightCheck(
        type = PreflightCheckType.PROFILE_COMPATIBILITY,
        severity = severity,
        status = status,
        message = "Profile check",
    )
}
