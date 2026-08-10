package dev.po4yka.lenswake.ui

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InsetPaddingTest {
    @Test
    fun `screen margins stay independent from scaffold insets`() {
        val padding = screenContentPadding(
            horizontalMargin = 20.dp,
            topMargin = 24.dp,
            bottomMargin = 28.dp,
        )

        assertEquals(20.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(20.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(20.dp, padding.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(20.dp, padding.calculateRightPadding(LayoutDirection.Rtl))
        assertEquals(24.dp, padding.calculateTopPadding())
        assertEquals(28.dp, padding.calculateBottomPadding())
    }
}
