package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.core.AutomationFailureCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PixelCameraCaptureRoutingTest : PixelCameraAccessibilityPortTestFixture() {
    @Test
    fun `missing night sight mode target reports its domain-specific failure`() = runTest {
        val result = port(gateway = FakeAccessibilityGateway())
            .selectNightSightTimeLapse(profileUse())

        val rejected = assertInstanceOf(ActionDispatch.Rejected::class.java, result)
        assertEquals(
            AutomationFailureCode.NIGHT_SIGHT_TIME_LAPSE_MODE_NOT_FOUND,
            rejected.failure.code,
        )
    }
}
