package dev.po4yka.lenswake.ui.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusVisualStateTest {
    @Test
    fun `maps product statuses to distinct visual states`() {
        assertEquals(StatusVisualState.ERROR, statusVisualState("Failed"))
        assertEquals(StatusVisualState.WARNING, statusVisualState("Needs rehearsal"))
        assertEquals(StatusVisualState.SUCCESS, statusVisualState("Passed"))
        assertEquals(StatusVisualState.IN_PROGRESS, statusVisualState("Running"))
        assertEquals(StatusVisualState.IN_PROGRESS, statusVisualState("Checking"))
        assertEquals(StatusVisualState.NEUTRAL, statusVisualState("Recorded event"))
        assertEquals(StatusVisualState.NEUTRAL, statusVisualState("Disabled"))
    }

    @Test
    fun `negative compound status wins over positive substring`() {
        assertEquals(StatusVisualState.ERROR, statusVisualState("Unavailable"))
        assertEquals(StatusVisualState.WARNING, statusVisualState("Ready with warnings"))
    }
}
