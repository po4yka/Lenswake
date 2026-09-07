package dev.po4yka.lenswake.ui.component

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

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

    @Test
    fun `status accents stay legible on the surface they are drawn on`() {
        listOf(lightColorScheme(), darkColorScheme()).forEach { scheme ->
            StatusVisualState.entries.forEach { state ->
                val ratio = contrastRatio(state.onSurfaceAccent(scheme), scheme.surface)
                assertTrue(ratio >= 4.5, "$state accent contrast is $ratio, below the 4.5:1 minimum")
            }
        }
    }
}

private fun contrastRatio(foreground: Color, background: Color): Double {
    val first = relativeLuminance(foreground)
    val second = relativeLuminance(background)
    return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val component = value.toDouble()
        return if (component <= 0.03928) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}
