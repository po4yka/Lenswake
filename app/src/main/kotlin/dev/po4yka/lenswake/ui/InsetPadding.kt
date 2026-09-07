package dev.po4yka.lenswake.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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

/**
 * Applies the scaffold padding, then caps the content column at [MAX_CONTENT_WIDTH] and centres it in
 * the remaining space so body text keeps a readable line length on expanded windows.
 *
 * `fillMaxWidth` is what keeps narrow windows unchanged: without it a lazy list whose items do not all
 * fill the width would shrink-wrap and drift to the centre on a phone.
 */
internal fun Modifier.scaffoldContentViewport(
    scaffoldPadding: PaddingValues,
): Modifier = padding(scaffoldPadding)
    .consumeWindowInsets(scaffoldPadding)
    .wrapContentWidth()
    .widthIn(max = MAX_CONTENT_WIDTH)
    .fillMaxWidth()

/** Material 3 caps a single content pane so long-form text never runs edge to edge. */
internal val MAX_CONTENT_WIDTH = 840.dp
