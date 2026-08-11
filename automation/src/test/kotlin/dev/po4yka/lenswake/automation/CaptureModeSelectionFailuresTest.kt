package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.CaptureMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CaptureModeSelectionFailuresTest {
    @Test
    fun `night sight time lapse has mode-specific dispatch and verification failures`() {
        assertEquals(
            CaptureModeSelectionFailures(
                dispatch = AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND,
                verification = AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_VERIFIED,
            ),
            CaptureMode.NIGHT_SIGHT_TIME_LAPSE.selectionFailureCodes,
        )
    }
}
