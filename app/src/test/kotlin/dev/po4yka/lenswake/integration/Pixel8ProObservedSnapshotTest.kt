package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.application.KnownPixelCameraProfileCatalog
import dev.po4yka.lenswake.automation.PixelCameraState
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.SelectorMatchResult
import dev.po4yka.lenswake.automation.SelectorMatcher
import dev.po4yka.lenswake.automation.UiNodeSnapshot
import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.LensSelection
import dev.po4yka.lenswake.core.NormalizedBounds
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class Pixel8ProObservedSnapshotTest {
    private val profile = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630
    private val matcher = SelectorMatcher()

    @Test
    fun `front one times zoom must not imply a rear lens`() {
        val result = PixelCameraStateInferer(matcher).infer(profile, observedNodes("front"))
        val observed = assertInstanceOf(PortResult.Observed::class.java, result)
        val state = assertInstanceOf(PixelCameraState.TimeLapse::class.java, observed.value)
        assertEquals(LensSelection.FRONT, state.lens)
    }

    @Test
    fun `night control presence is unique while both options are visible`() {
        assertInstanceOf(
            SelectorMatchResult.Match::class.java,
            matcher.match(profile.stateSignals.getValue(PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_CONTROL_OPEN),
                profile, observedNodes("night-panel")),
        )
    }

    @Test
    fun `night entry does not depend on minibar position`() {
        val result = matcher.match(AutomationAction.OPEN_NIGHT_SIGHT_TIME_LAPSE_CONTROL,
            profile, observedNodes("timelapse"))
        val match = assertInstanceOf(SelectorMatchResult.Match::class.java, result)
        assertEquals("Time Lapse settings", match.node.contentDescription)
    }

    @Test
    fun `closed speed label and open picker both report their selected values`() {
        val inferer = PixelCameraStateInferer(matcher)
        val closed = assertInstanceOf(PortResult.Observed::class.java,
            inferer.infer(profile, observedNodes("speed-30")))
        assertEquals(dev.po4yka.lenswake.core.TimeLapseSpeed.X30,
            assertInstanceOf(PixelCameraState.TimeLapse::class.java, closed.value).speed)
        val open = assertInstanceOf(PortResult.Observed::class.java,
            inferer.infer(profile, observedNodes("speed-picker")))
        assertEquals(dev.po4yka.lenswake.core.TimeLapseSpeed.AUTO,
            assertInstanceOf(PixelCameraState.TimeLapseSpeedPicker::class.java, open.value).speed)
    }

    @Test
    fun `video settings targets select buttons instead of separate labels`() {
        listOf(AutomationAction.SELECT_VIDEO_RESOLUTION_4K, AutomationAction.SELECT_VIDEO_FRAME_RATE_60)
            .forEach { action ->
                val result = matcher.match(action, profile, observedNodes("video-settings"))
                val match = assertInstanceOf(SelectorMatchResult.Match::class.java, result)
                assertEquals("android.widget.ImageButton", match.node.role)
            }
    }
}

internal fun observedNodes(name: String): List<UiNodeSnapshot> {
    val resource = requireNotNull(Pixel8ProObservedSnapshotTest::class.java
        .getResourceAsStream("/pixel-8-pro-2026-09-09/$name.xml"))
    val document = resource.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
    val nodes = document.getElementsByTagName("node")
    return (0 until nodes.length).map { index ->
        val element = nodes.item(index) as Element
        fun value(key: String) = element.getAttribute(key).ifEmpty { null }
        val bounds = Regex("\\d+").findAll(element.getAttribute("bounds")).map { it.value.toFloat() }.toList()
        UiNodeSnapshot(
            id = "observed-$index", packageName = value("package"), resourceId = value("resource-id"),
            role = value("class"), contentDescription = value("content-desc"), text = value("text"),
            bounds = NormalizedBounds(bounds[0] / 1008f, bounds[1] / 2244f,
                bounds[2] / 1008f, bounds[3] / 2244f),
            visible = true, clickable = value("clickable") == "true", selected = value("selected") == "true",
            checkable = value("checkable") == "true", checked = value("checked") == "true",
            enabled = value("enabled") == "true",
        )
    }
}
