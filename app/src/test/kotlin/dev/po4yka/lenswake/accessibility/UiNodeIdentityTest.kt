package dev.po4yka.lenswake.accessibility

import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.NormalizedBounds
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiNodeIdentityTest {
    @Test
    fun `duplicate semantic control at different bounds is not the selected target`() {
        val selected = node(bounds = NormalizedBounds(0.1f, 0.7f, 0.3f, 0.9f))
        val reboundAtStalePath = node(bounds = NormalizedBounds(0.7f, 0.7f, 0.9f, 0.9f))

        assertFalse(reboundAtStalePath.hasSameInteractionIdentityAs(selected))
    }

    @Test
    fun `path changes do not invalidate an otherwise identical target`() {
        val selected = node(id = "root/2")
        val moved = node(id = "root/3")

        assertTrue(moved.hasSameInteractionIdentityAs(selected))
    }

    private fun node(
        id: String = "root/2",
        bounds: NormalizedBounds = NormalizedBounds(0.1f, 0.7f, 0.3f, 0.9f),
    ): UiNodeSnapshot = UiNodeSnapshot(
        id = id,
        packageName = "com.google.android.GoogleCamera",
        resourceId = "com.google.android.GoogleCamera:id/record_button",
        role = "android.widget.Button",
        contentDescription = "Record",
        text = null,
        bounds = bounds,
        visible = true,
        clickable = true,
        selected = false,
        enabled = true,
    )
}
