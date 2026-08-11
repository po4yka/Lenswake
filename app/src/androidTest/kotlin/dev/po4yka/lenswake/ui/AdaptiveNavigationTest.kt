package dev.po4yka.lenswake.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.theme.LenswakeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandedWindowUsesPermanentDrawerAtTheStartEdge() {
        val (root, schedules) = renderApp(width = 900.dp, height = 500.dp)

        composeRule.onNodeWithTag(NAVIGATION_DRAWER_TAG).assertExists()
        composeRule.onNodeWithTag(NAVIGATION_RAIL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(NAVIGATION_BAR_TAG).assertDoesNotExist()

        assertTrue(
            "Expanded-window navigation was not placed at the start edge: $schedules",
            schedules.centerX < root.centerX,
        )
    }

    @Test
    fun mediumWindowUsesNavigationRailAtTheStartEdge() {
        val (root, schedules) = renderApp(width = 700.dp, height = 500.dp)

        composeRule.onNodeWithTag(NAVIGATION_RAIL_TAG).assertExists()
        composeRule.onNodeWithTag(NAVIGATION_DRAWER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(NAVIGATION_BAR_TAG).assertDoesNotExist()

        assertTrue(
            "Medium-window navigation was not placed at the start edge: $schedules",
            schedules.centerX < (root.left + 100.dp).value,
        )
    }

    @Test
    fun compactWindowUsesNavigationBarAtTheBottomEdge() {
        val (root, schedules) = renderApp(width = 400.dp, height = 800.dp)

        composeRule.onNodeWithTag(NAVIGATION_BAR_TAG).assertExists()
        composeRule.onNodeWithTag(NAVIGATION_RAIL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(NAVIGATION_DRAWER_TAG).assertDoesNotExist()

        assertTrue(
            "Compact-window navigation did not remain at the bottom: $schedules",
            schedules.centerY > (root.bottom - 100.dp).value,
        )
    }

    private fun renderApp(width: Dp, height: Dp): Pair<DpRect, DpRect> {
        composeRule.setContent {
            LenswakeTheme(dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .requiredSize(width = width, height = height)
                        .testTag(ROOT_TAG),
                ) {
                    LenswakeApp(state = LenswakeUiState())
                }
            }
        }

        return composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot() to
            composeRule.onNode(hasText("Schedules") and hasClickAction())
                .getUnclippedBoundsInRoot()
    }

    private companion object {
        const val ROOT_TAG = "adaptive-navigation-test-root"
    }
}

private val DpRect.centerX: Float
    get() = (left.value + right.value) / 2f

private val DpRect.centerY: Float
    get() = (top.value + bottom.value) / 2f
