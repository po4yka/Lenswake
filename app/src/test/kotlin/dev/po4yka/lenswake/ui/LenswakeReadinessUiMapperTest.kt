package dev.po4yka.lenswake.ui

import dev.po4yka.lenswake.core.PreflightCheck
import dev.po4yka.lenswake.core.PreflightCheckType
import dev.po4yka.lenswake.core.PreflightSeverity
import dev.po4yka.lenswake.core.PreflightStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LenswakeReadinessUiMapperTest {
    @Test
    fun advisoryPowerFailureIsAWarningRatherThanABlocker() {
        val capability = capability(PreflightSeverity.WARNING, PreflightStatus.FAILED)
        assertEquals(CapabilityStatus.WARNING, capability.status)
        assertFalse(capability.required)
    }

    @Test
    fun unknownRequiredResourceStillBlocks() {
        val capability = capability(PreflightSeverity.BLOCKING, PreflightStatus.UNKNOWN)
        assertEquals(CapabilityStatus.UNKNOWN, capability.status)
        assertTrue(capability.required)
    }

    @Test
    fun unavailableInformationalFeatureIsExplicitlyNotUsed() {
        val capability = capability(PreflightSeverity.INFO, PreflightStatus.UNKNOWN)
        assertEquals(CapabilityStatus.NOT_USED, capability.status)
        assertFalse(capability.required)
    }

    private fun capability(severity: PreflightSeverity, status: PreflightStatus) =
        LenswakeReadinessUiMapper.capability(
            PreflightCheck(PreflightCheckType.CHARGING, severity, status, "Observed power state"),
            TestUiStringProvider,
        )
}
