package dev.po4yka.lenswake.automation

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.UiSelector
import dev.po4yka.lenswake.core.UiSelectorSet
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SelectorMatcherTest {
    private val bounds = NormalizedBounds(0.4f, 0.7f, 0.6f, 0.9f)
    private val selector = UiSelector(
        packageName = CAMERA_PACKAGE,
        resourceId = "verified/resource",
        role = "android.widget.Button",
        contentDescription = "Verified description",
        text = "Verified text",
        expectedRegion = NormalizedBounds(0.3f, 0.6f, 0.7f, 1f),
    )

    @Test
    fun `profile selector chooses the highest scoring eligible node`() {
        val result = SelectorMatcher().match(
            action = AutomationAction.START_RECORDING,
            profile = profile(minimumScore = 100),
            nodes = listOf(
                node(id = "description-only", resourceId = null, role = null, text = null),
                node(id = "exact"),
            ),
        )

        val match = assertInstanceOf(SelectorMatchResult.Match::class.java, result)
        assertEquals("exact", match.node.id)
        assertEquals(230, match.score)
        assertEquals(100, match.minimumScore)
    }

    @Test
    fun `wrong-package and invisible nodes are excluded even when otherwise exact`() {
        val result = SelectorMatcher().match(
            action = AutomationAction.START_RECORDING,
            profile = profile(minimumScore = 50),
            nodes = listOf(
                node(id = "wrong-package", packageName = "example.other"),
                node(id = "invisible", visible = false),
            ),
        )

        assertInstanceOf(SelectorMatchResult.NoEligibleNodes::class.java, result)
    }

    @Test
    fun `best candidate below the profile threshold is rejected`() {
        val result = SelectorMatcher().match(
            action = AutomationAction.START_RECORDING,
            profile = profile(minimumScore = 200),
            nodes = listOf(node(id = "description-only", resourceId = null, role = null, text = null)),
        )

        val rejection = assertInstanceOf(SelectorMatchResult.BelowThreshold::class.java, result)
        assertEquals(80, rejection.bestScore)
        assertEquals(200, rejection.minimumScore)
    }

    @Test
    fun `equal top candidates are explicitly ambiguous`() {
        val result = SelectorMatcher().match(
            action = AutomationAction.START_RECORDING,
            profile = profile(minimumScore = 100),
            nodes = listOf(node(id = "first"), node(id = "second")),
        )

        val ambiguous = assertInstanceOf(SelectorMatchResult.Ambiguous::class.java, result)
        assertEquals(setOf("first", "second"), ambiguous.candidates.map { it.node.id }.toSet())
        assertEquals(230, ambiguous.score)
    }

    @Test
    fun `selected state constraint excludes inactive duplicate`() {
        val selectedSelector = selector.copy(expectedSelected = true)
        val selectedProfile = profile(minimumScore = 100).copy(
            targets = mapOf(
                AutomationAction.START_RECORDING to UiSelectorSet(
                    selectors = listOf(selectedSelector),
                    minimumScore = 100,
                ),
            ),
        )

        val result = SelectorMatcher().match(
            action = AutomationAction.START_RECORDING,
            profile = selectedProfile,
            nodes = listOf(
                node(id = "inactive", selected = false),
                node(id = "active", selected = true),
            ),
        )

        val match = assertInstanceOf(SelectorMatchResult.Match::class.java, result)
        assertEquals("active", match.node.id)
        assertEquals(245, match.score)
    }

    @Test
    fun `checked state constraint excludes unchecked duplicate`() {
        val checkedSelector = selector.copy(expectedChecked = true)
        val checkedProfile = profile(minimumScore = 100).copy(
            targets = mapOf(
                AutomationAction.START_RECORDING to UiSelectorSet(
                    selectors = listOf(checkedSelector),
                    minimumScore = 100,
                ),
            ),
        )

        val result = SelectorMatcher().match(
            action = AutomationAction.START_RECORDING,
            profile = checkedProfile,
            nodes = listOf(
                node(id = "unchecked", checked = false),
                node(id = "checked", checked = true),
            ),
        )

        val match = assertInstanceOf(SelectorMatchResult.Match::class.java, result)
        assertEquals("checked", match.node.id)
        assertEquals(245, match.score)
        assertEquals(true, SelectorSignal.CHECKED_STATE in match.matchedSignals)
    }

    @Test
    fun `clickable state cannot meet threshold when configured discriminant misses`() {
        val selectorSet = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = CAMERA_PACKAGE,
                    resourceId = "configured-but-not-present",
                ),
            ),
            minimumScore = 10,
        )

        val result = SelectorMatcher().match(
            selectorSet = selectorSet,
            profile = profile(minimumScore = 100),
            nodes = listOf(node(id = "clickable-only")),
        )

        assertInstanceOf(SelectorMatchResult.NoEligibleNodes::class.java, result)
    }

    @Test
    fun `selected and clickable state cannot identify a node without a discriminant`() {
        val selectorSet = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = CAMERA_PACKAGE,
                    expectedSelected = true,
                ),
            ),
            minimumScore = 25,
        )

        val result = SelectorMatcher().match(
            selectorSet = selectorSet,
            profile = profile(minimumScore = 100),
            nodes = listOf(node(id = "state-only", selected = true)),
        )

        assertInstanceOf(SelectorMatchResult.NoEligibleNodes::class.java, result)
    }

    @Test
    fun `checked state cannot identify a node without a discriminant`() {
        val selectorSet = UiSelectorSet(
            selectors = listOf(
                UiSelector(
                    packageName = CAMERA_PACKAGE,
                    expectedChecked = true,
                    requiresClickable = false,
                ),
            ),
            minimumScore = 15,
        )

        val result = SelectorMatcher().match(
            selectorSet = selectorSet,
            profile = profile(minimumScore = 100),
            nodes = listOf(node(id = "checked-state-only", checked = true)),
        )

        assertInstanceOf(SelectorMatchResult.NoEligibleNodes::class.java, result)
    }

    private fun profile(minimumScore: Int) = PixelCameraProfile(
        id = ProfileId("profile"),
        environment = PixelCameraEnvironment(
            deviceManufacturer = "Google",
            deviceModel = "Pixel 8 Pro",
            androidSdk = 37,
            androidBuildFingerprint = "verified",
            cameraPackage = CAMERA_PACKAGE,
            cameraVersionCode = 1,
            localeTag = "en-US",
            displayWidthPx = 1344,
            displayHeightPx = 2992,
            densityDpi = 480,
        ),
        selectorSchemaVersion = PixelCameraSelectorSchema.CURRENT_VERSION,
        targets = mapOf(
            AutomationAction.START_RECORDING to UiSelectorSet(
                selectors = listOf(selector),
                minimumScore = minimumScore,
            ),
        ),
        compatibility = ProfileCompatibility.VERIFIED,
        verifiedAt = Instant.parse("2026-08-09T10:00:00Z"),
    )

    private fun node(
        id: String,
        packageName: String = CAMERA_PACKAGE,
        resourceId: String? = "verified/resource",
        role: String? = "android.widget.Button",
        text: String? = "Verified text",
        visible: Boolean = true,
        selected: Boolean = false,
        checked: Boolean = false,
    ) = UiNodeSnapshot(
        id = id,
        packageName = packageName,
        resourceId = resourceId,
        role = role,
        contentDescription = "Verified description",
        text = text,
        bounds = bounds,
        visible = visible,
        clickable = true,
        selected = selected,
        checkable = true,
        checked = checked,
        enabled = true,
    )

    private companion object {
        const val CAMERA_PACKAGE = "com.google.android.GoogleCamera"
    }
}
