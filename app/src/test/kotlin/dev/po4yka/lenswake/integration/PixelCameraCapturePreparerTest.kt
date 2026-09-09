package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.accessibility.AccessibilityDispatchResult
import dev.po4yka.lenswake.accessibility.AccessibilitySnapshotResult
import dev.po4yka.lenswake.application.KnownPixelCameraProfileCatalog
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.CaptureConfiguration
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.NormalizedPoint
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Replays observed UI shapes; the gateway models settings postconditions, not physical evidence. */
class PixelCameraCapturePreparerTest {
    private val profile = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630
    private val capture = CaptureConfiguration.Video(lens = LensSelection.FRONT)

    @Test
    fun `video preparation verifies settings and closes panel before returning evidence`() = runTest {
        val gateway = SettingsGateway()
        val result = preparer(gateway).prepare(capture, ProfileUse(profile, ProfileUse.Kind.REHEARSAL))
        assertEquals(PortResult.Observed(capture), result)
        assertEquals(listOf("Video settings", "60 FPS", "back"), gateway.actions)
        assertEquals(false, gateway.panelOpen)
    }

    @Test
    fun `accepted frame rate click without selected postcondition cannot prepare capture`() = runTest {
        val gateway = SettingsGateway(confirmFrameRate = false)
        val result = preparer(gateway).prepare(capture, ProfileUse(profile, ProfileUse.Kind.REHEARSAL))
        assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(listOf("Video settings", "60 FPS"), gateway.actions)
    }

    @Test
    fun `changed lens after panel dismissal invalidates settings evidence`() = runTest {
        val gateway = SettingsGateway(changeLensOnClose = true)
        val result = preparer(gateway).prepare(capture, ProfileUse(profile, ProfileUse.Kind.REHEARSAL))
        assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertTrue(gateway.actions.none { it.startsWith("Start") })
    }

    @Test
    fun `ambiguous settings panel never permits a settings action`() = runTest {
        val gateway = SettingsGateway(duplicatePanel = true)
        val result = preparer(gateway).prepare(capture, ProfileUse(profile, ProfileUse.Kind.REHEARSAL))
        assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(listOf("Video settings"), gateway.actions)
    }

    @Test
    fun `native unavailable night setting does not block ordinary front time lapse`() = runTest {
        val gateway = UnavailableNightGateway()
        val normal = CaptureConfiguration.TimeLapse(TimeLapseSpeed.AUTO, LensSelection.FRONT)
        val result = preparer(gateway).prepare(normal, ProfileUse(profile, ProfileUse.Kind.REHEARSAL))
        assertEquals(PortResult.Observed(normal), result)
        assertEquals(listOf("Time Lapse settings", "back"), gateway.actions)
    }

    @Test
    fun `native unavailable night setting returns a typed rejection and closes its panel`() = runTest {
        val gateway = UnavailableNightGateway()
        val night = CaptureConfiguration.NightSightTimeLapse(LensSelection.FRONT)
        val result = preparer(gateway).prepare(night, ProfileUse(profile, ProfileUse.Kind.REHEARSAL))
        val unavailable = assertInstanceOf(PortResult.Unavailable::class.java, result)
        assertEquals(AutomationFailureCode.UNSUPPORTED_CAPTURE_CONFIGURATION, unavailable.failure.code)
        assertEquals(listOf("Time Lapse settings", "back"), gateway.actions)
    }

    private fun preparer(gateway: PixelCameraAccessibilityGateway) = PixelCameraCapturePreparer(
        SelectorMatcher(), gateway, PixelCameraProfileValidator { PortResult.Observed(profile.environment) },
    )
}

private class SettingsGateway(
    private val confirmFrameRate: Boolean = true,
    private val changeLensOnClose: Boolean = false,
    private val duplicatePanel: Boolean = false,
) : PixelCameraAccessibilityGateway {
    val actions = mutableListOf<String>()
    var panelOpen = false
    private var frameRate60 = false
    private var closed = false

    override suspend fun snapshot(): AccessibilitySnapshotResult {
        val nodes = if (panelOpen) {
            observedNodes("video-settings").map { node ->
                if (node.role == "android.widget.ImageButton" && node.contentDescription == "60 FPS") {
                    node.copy(selected = frameRate60)
                } else if (node.role == "android.widget.ImageButton" && node.contentDescription == "30 FPS") {
                    node.copy(selected = !frameRate60)
                } else node
            }.let { original ->
                if (!duplicatePanel) original else original + original.single {
                    it.resourceId?.endsWith("/pinned_panel") == true
                }.copy(id = "duplicate-panel")
            }
        } else observedNodes(if (closed && changeLensOnClose) "video" else "video-60")
        return AccessibilitySnapshotResult.Available(nodes, truncated = false)
    }

    override suspend fun dispatchClick(node: UiNodeSnapshot): AccessibilityDispatchResult {
        actions += requireNotNull(node.contentDescription)
        when (node.contentDescription) {
            "Video settings" -> panelOpen = true
            "60 FPS" -> frameRate60 = confirmFrameRate
            else -> error("Unexpected configuration action: ${node.contentDescription}")
        }
        return AccessibilityDispatchResult.SemanticActionDispatched
    }

    override suspend fun dispatchGlobalBack(pickerNode: UiNodeSnapshot): AccessibilityDispatchResult {
        assertTrue(pickerNode.resourceId?.endsWith("/pinned_panel") == true)
        actions += "back"
        panelOpen = false
        closed = true
        return AccessibilityDispatchResult.GlobalActionDispatched
    }

    override suspend fun dispatchProfileGesture(point: NormalizedPoint): AccessibilityDispatchResult =
        error("Settings preparation must use observed semantic controls")
}

private class UnavailableNightGateway : PixelCameraAccessibilityGateway {
    val actions = mutableListOf<String>()
    private var panelOpen = false

    override suspend fun snapshot() = AccessibilitySnapshotResult.Available(
        observedNodes(if (panelOpen) "front-tl-settings" else "front"), truncated = false,
    )

    override suspend fun dispatchClick(node: UiNodeSnapshot): AccessibilityDispatchResult {
        assertEquals("Time Lapse settings", node.contentDescription)
        actions += requireNotNull(node.contentDescription)
        panelOpen = true
        return AccessibilityDispatchResult.SemanticActionDispatched
    }

    override suspend fun dispatchGlobalBack(pickerNode: UiNodeSnapshot): AccessibilityDispatchResult {
        assertEquals("Time Lapse settings", pickerNode.contentDescription)
        actions += "back"
        panelOpen = false
        return AccessibilityDispatchResult.GlobalActionDispatched
    }

    override suspend fun dispatchProfileGesture(point: NormalizedPoint): AccessibilityDispatchResult =
        error("No gesture may bypass a native unavailable setting")
}
