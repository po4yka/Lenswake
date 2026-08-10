package dev.po4yka.lenswake.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun screenContentPadding(
    horizontalMargin: Dp = 20.dp,
    topMargin: Dp,
    bottomMargin: Dp,
): PaddingValues = PaddingValues(
    start = horizontalMargin,
    top = topMargin,
    end = horizontalMargin,
    bottom = bottomMargin,
)

internal fun Modifier.scaffoldContentViewport(
    scaffoldPadding: PaddingValues,
): Modifier = padding(scaffoldPadding).consumeWindowInsets(scaffoldPadding)
