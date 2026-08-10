package dev.po4yka.lenswake.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InsetPaddingTest {
    @Test
    fun `screen margins preserve asymmetric scaffold insets in both layout directions`() {
        val padding = screenContentPadding(
            scaffoldPadding = PaddingValues(
                start = 11.dp,
                top = 13.dp,
                end = 17.dp,
                bottom = 19.dp,
            ),
            horizontalMargin = 20.dp,
            topMargin = 24.dp,
            bottomMargin = 28.dp,
        )

        assertEquals(31.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(37.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(37.dp, padding.calculateLeftPadding(LayoutDirection.Rtl))
        assertEquals(31.dp, padding.calculateRightPadding(LayoutDirection.Rtl))
        assertEquals(37.dp, padding.calculateTopPadding())
        assertEquals(47.dp, padding.calculateBottomPadding())
    }
}
