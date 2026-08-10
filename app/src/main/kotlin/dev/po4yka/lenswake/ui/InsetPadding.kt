package dev.po4yka.lenswake.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.plus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun screenContentPadding(
    scaffoldPadding: PaddingValues,
    horizontalMargin: Dp = 20.dp,
    topMargin: Dp,
    bottomMargin: Dp,
): PaddingValues =
    scaffoldPadding + PaddingValues(
        start = horizontalMargin,
        top = topMargin,
        end = horizontalMargin,
        bottom = bottomMargin,
    )
