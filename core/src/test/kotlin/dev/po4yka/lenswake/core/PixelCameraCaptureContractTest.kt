package dev.po4yka.lenswake.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PixelCameraCaptureContractTest {
    @Test
    fun `capture modes route through their calibrated actions and signals`() {
        assertEquals(
            AutomationAction.START_VIDEO_RECORDING,
            CaptureMode.VIDEO.pixelCameraContract.startAction,
        )
        assertEquals(
            AutomationAction.STOP_RECORDING,
            CaptureMode.TIME_LAPSE.pixelCameraContract.stopAction,
        )
        assertEquals(
            setOf(
                PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_CONTROL_OPEN,
                PixelCameraStateSignal.NIGHT_SIGHT_TIME_LAPSE_MODE_ACTIVE,
            ),
            CaptureMode.NIGHT_SIGHT_TIME_LAPSE.pixelCameraContract.requiredSignals,
        )
        assertEquals(
            setOf(
                AutomationAction.SELECT_TIME_LAPSE,
                AutomationAction.OPEN_NIGHT_SIGHT_TIME_LAPSE_CONTROL,
                AutomationAction.SELECT_NIGHT_SIGHT_TIME_LAPSE,
            ),
            CaptureMode.NIGHT_SIGHT_TIME_LAPSE.pixelCameraContract.preparationActions,
        )
    }

    @Test
    fun `time lapse requirements include exact speed lens and mode routes`() {
        val requirements =
            CaptureConfiguration
                .TimeLapse(
                    speed = TimeLapseSpeed.X30,
                    lens = LensSelection.REAR_TELEPHOTO,
                ).pixelCameraRequirements

        assertTrue(AutomationAction.SELECT_TIME_LAPSE in requirements.actions)
        assertTrue(AutomationAction.SELECT_REAR_TELEPHOTO_LENS in requirements.actions)
        assertTrue(PixelCameraStateSignal.TIME_LAPSE_SPEED_X30_ACTIVE in requirements.signals)
        assertTrue(PixelCameraStateSignal.REAR_TELEPHOTO_LENS_ACTIVE in requirements.signals)
        assertEquals(TimeLapseSpeed.X30, requirements.speedTarget)
    }
}
