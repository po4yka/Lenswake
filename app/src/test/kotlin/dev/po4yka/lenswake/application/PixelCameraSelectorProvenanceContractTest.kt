package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.AutomationAction
import dev.po4yka.lenswake.core.PixelCameraStateSignal
import dev.po4yka.lenswake.core.TimeLapseSpeed
import dev.po4yka.lenswake.core.UiSelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PixelCameraSelectorProvenanceContractTest {
    private val profile = KnownPixelCameraProfileCatalog.pixel8ProAndroid17Camera69481630

    @Test
    fun `video selectors match the version-pinned APK resources and call sites`() {
        assertAction(
            AutomationAction.SELECT_VIDEO_RESOLUTION_4K,
            description = "4K Ultra HD",
            role = "android.widget.ImageButton",
        )
        assertAction(
            AutomationAction.SELECT_VIDEO_FRAME_RATE_60,
            description = "60 FPS",
            role = "android.widget.ImageButton",
        )
        assertSignal(
            PixelCameraStateSignal.VIDEO_RESOLUTION_4K_ACTIVE,
            description = "4K Ultra HD",
            role = "android.widget.ImageButton",
        )
        assertSignal(
            PixelCameraStateSignal.VIDEO_FRAME_RATE_60_ACTIVE,
            description = "60 FPS",
            role = "android.widget.ImageButton",
        )
    }

    @Test
    fun `time lapse speed selectors match the version-pinned formatting resources`() {
        val expected =
            mapOf(
                TimeLapseSpeed.AUTO to ("Time Lapse auto speed" to "Auto"),
                TimeLapseSpeed.X5 to ("Time Lapse 5 times speed" to "5×"),
                TimeLapseSpeed.X10 to ("Time Lapse 10 times speed" to "10×"),
                TimeLapseSpeed.X30 to ("Time Lapse 30 times speed" to "30×"),
                TimeLapseSpeed.X120 to ("Time Lapse 120 times speed" to "120×"),
            )

        expected.forEach { (speed, values) ->
            val selector =
                profile.speedTargets
                    .getValue(speed)
                    .selectors
                    .single()
            assertEquals(values.first, selector.contentDescription, speed.name)
            assertEquals(values.second, selector.text, speed.name)
        }
    }

    @Test
    fun `lens and night selectors match the version-pinned APK resources`() {
        assertEquals(setOf("zoom_toggle_.5", "zoom_toggle_.5×"),
            profile.targets.getValue(AutomationAction.SELECT_REAR_ULTRAWIDE_LENS)
                .selectors.map { it.resourceId }.toSet())
        assertEquals(setOf("zoom_toggle_5", "zoom_toggle_5×"),
            profile.targets.getValue(AutomationAction.SELECT_REAR_TELEPHOTO_LENS)
                .selectors.map { it.resourceId }.toSet())
        assertAction(AutomationAction.SELECT_FRONT_LENS, description = "Switch to front camera")
        assertSignal(PixelCameraStateSignal.FRONT_LENS_ACTIVE, description = "Switch to back camera")
        assertAction(
            AutomationAction.OPEN_NIGHT_SIGHT_TIME_LAPSE_CONTROL,
            description = "Time Lapse settings",
            resource = "com.google.android.GoogleCamera:id/options_entry_button",
        )
        assertAction(
            AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE,
            description = "Auto Night Sight in Time Lapse on",
            role = "android.widget.ImageButton",
        )
        assertSignal(
            PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE,
            description = "Auto Night Sight in Time Lapse on",
            role = "android.widget.ImageButton",
        )
        // A single option anchors the open panel even when both option buttons are visible.
        val controlOpen =
            profile.stateSignals.getValue(PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_CONTROL_OPEN)
        assertEquals(
            listOf("Auto Night Sight in Time Lapse on"),
            controlOpen.selectors.map(UiSelector::contentDescription),
        )
        assertEquals(
            listOf("android.widget.ImageButton"),
            controlOpen.selectors.map(UiSelector::role),
        )
    }

    private fun assertAction(
        action: AutomationAction,
        description: String? = null,
        text: String? = null,
        resource: String? = null,
        role: String? = null,
    ) {
        val selector =
            profile.targets
                .getValue(action)
                .selectors
                .single()
        assertEquals(description, selector.contentDescription, action.name)
        assertEquals(text, selector.text, action.name)
        assertEquals(resource, selector.resourceId, action.name)
        assertEquals(role, selector.role, action.name)
    }

    private fun assertSignal(
        signal: PixelCameraStateSignal,
        description: String? = null,
        text: String? = null,
        resource: String? = null,
        role: String? = null,
    ) {
        val selector =
            profile.stateSignals
                .getValue(signal)
                .selectors
                .single()
        assertEquals(description, selector.contentDescription, signal.name)
        assertEquals(text, selector.text, signal.name)
        assertEquals(resource, selector.resourceId, signal.name)
        assertEquals(role, selector.role, signal.name)
    }
}
