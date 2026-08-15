package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.TimeLapseSpeed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PixelCameraSelectorProvenanceContractTest {
    private val profile = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630

    @Test
    fun `video selectors match the version-pinned APK resources and call sites`() {
        assertAction(
            AutomationAction.SELECT_VIDEO_RESOLUTION_4K,
            description = "4K Ultra HD",
            text = "4K (Ultra HD)",
        )
        assertAction(
            AutomationAction.SELECT_VIDEO_FRAME_RATE_60,
            description = "60 FPS",
            text = "60",
        )
        assertSignal(
            PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE,
            description = "4K Ultra HD",
            text = "4K (Ultra HD)",
        )
        assertSignal(
            PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE,
            description = "60 FPS",
            text = "60",
        )
    }

    @Test
    fun `time lapse speed selectors match the version-pinned formatting resources`() {
        val expected = mapOf(
            TimeLapseSpeed.AUTO to ("Time Lapse auto speed" to "Auto"),
            TimeLapseSpeed.X5 to ("Time Lapse 5 times speed" to "5×"),
            TimeLapseSpeed.X10 to ("Time Lapse 10 times speed" to "10×"),
            TimeLapseSpeed.X30 to ("Time Lapse 30 times speed" to "30×"),
            TimeLapseSpeed.X120 to ("Time Lapse 120 times speed" to "120×"),
        )

        expected.forEach { (speed, values) ->
            val selector = profile.speedTargets.getValue(speed).selectors.single()
            assertEquals(values.first, selector.contentDescription, speed.name)
            assertEquals(values.second, selector.text, speed.name)
        }
    }

    @Test
    fun `lens and night selectors match the version-pinned APK resources`() {
        assertAction(AutomationAction.SELECT_REAR_ULTRAWIDE_LENS, description = "Ultrawide")
        assertAction(AutomationAction.SELECT_REAR_TELEPHOTO_LENS, description = "Tele")
        assertAction(AutomationAction.SELECT_FRONT_LENS, description = "Switch to front camera")
        assertSignal(PixelCameraStateSignal.FRONT_LENS_ACTIVE, description = "Switch to back camera")
        assertAction(AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE, text = "Night Sight")
        assertSignal(
            PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE,
            text = "Night Sight auto enabled. Learn more",
        )
    }

    private fun assertAction(
        action: AutomationAction,
        description: String? = null,
        text: String? = null,
    ) {
        val selector = profile.targets.getValue(action).selectors.single()
        assertEquals(description, selector.contentDescription, action.name)
        assertEquals(text, selector.text, action.name)
    }

    private fun assertSignal(
        signal: PixelCameraStateSignal,
        description: String? = null,
        text: String? = null,
    ) {
        val selector = profile.stateSignals.getValue(signal).selectors.single()
        assertEquals(description, selector.contentDescription, signal.name)
        assertEquals(text, selector.text, signal.name)
    }
}
