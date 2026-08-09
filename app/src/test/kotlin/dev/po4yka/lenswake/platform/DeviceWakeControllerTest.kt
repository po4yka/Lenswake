package dev.po4yka.lenswake.platform

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DeviceWakeControllerTest {
    @Test
    fun unavailableControllerReportsTheSameTypedReasonForReadinessAndDispatch() = runTest {
        val controller = UnavailableDeviceWakeController()

        val availability = assertInstanceOf(
            PlatformCapability.Unavailable::class.java,
            controller.availability(),
        )
        val dispatch = assertInstanceOf(
            PlatformCapability.Unavailable::class.java,
            controller.wakeDevice(),
        )

        assertEquals(PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH, availability.code)
        assertEquals(availability.code, dispatch.code)
        assertEquals(availability.detail, dispatch.detail)
    }
}
