package dev.po4yka.lenswake.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.po4yka.lenswake.ui.screen.ProfilesScreen
import dev.po4yka.lenswake.ui.theme.LenswakeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InsetLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ltrContentPreservesPhysicalSystemInsets() {
        val (root, title) = renderProfileScreen(LayoutDirection.Ltr)

        assertDpEquals(root.left + 51.dp, title.left)
        assertDpEquals(root.top + 61.dp, title.top)
    }

    @Test
    fun rtlContentPreservesPhysicalSystemInsets() {
        val (root, title) = renderProfileScreen(LayoutDirection.Rtl)

        assertDpEquals(root.right - 63.dp, title.right)
        assertDpEquals(root.top + 61.dp, title.top)
    }

    @Test
    fun scrolledContentCannotEnterStatusBarInset() {
        val (root, _) = renderProfileScreen(LayoutDirection.Ltr)

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        val emptyState = composeRule.onNodeWithText("No profiles").getUnclippedBoundsInRoot()

        assertTrue(
            "Scrolled content entered the 37dp status-bar inset: ${emptyState.top}",
            emptyState.top >= root.top + 37.dp,
        )
    }

    @Test
    fun scrollViewportStartsBelowStatusBarInset() {
        val (root, _) = renderProfileScreen(LayoutDirection.Ltr)

        val viewport = composeRule.onNode(hasScrollToIndexAction()).getUnclippedBoundsInRoot()

        assertDpEquals(root.top + 37.dp, viewport.top)
    }

    @Test
    fun scrollViewportEndsAboveNavigationInset() {
        val (root, _) = renderProfileScreen(LayoutDirection.Ltr)

        val viewport = composeRule.onNode(hasScrollToIndexAction()).getUnclippedBoundsInRoot()

        assertDpEquals(root.bottom - 47.dp, viewport.bottom)
    }

    @Test
    fun lastContentRemainsReachableAboveNavigationInset() {
        val (root, _) = renderProfileScreen(LayoutDirection.Ltr)

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        val lastContent = composeRule
            .onNodeWithText("Production rehearsal not run")
            .getUnclippedBoundsInRoot()

        assertTrue(
            "Last content entered the 47dp navigation inset: ${lastContent.bottom}",
            lastContent.bottom <= root.bottom - 47.dp,
        )
    }

    private fun renderProfileScreen(layoutDirection: LayoutDirection): Pair<DpRect, DpRect> {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                LenswakeTheme(dynamicColor = false) {
                    Box(
                        modifier = Modifier
                            .requiredSize(400.dp)
                            .testTag(ROOT_TAG),
                    ) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets(
                                left = 31.dp,
                                top = 37.dp,
                                right = 43.dp,
                                bottom = 47.dp,
                            ),
                        ) { contentPadding ->
                            ProfilesScreen(
                                state = LenswakeUiState(),
                                contentPadding = contentPadding,
                                onOpenSetup = {},
                                onInstallCandidateProfile = {},
                                onRunRehearsal = {},
                            )
                        }
                    }
                }
            }
        }

        return composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot() to
            composeRule.onNodeWithText("Profiles").getUnclippedBoundsInRoot()
    }

    private fun assertDpEquals(expected: Dp, actual: Dp) {
        assertEquals(expected.value, actual.value, TOLERANCE_DP)
    }

    private companion object {
        const val ROOT_TAG = "inset-test-root"
        const val TOLERANCE_DP = 0.6f
    }
}
