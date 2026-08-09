package dev.po4yka.lenswake.platform

interface DeviceWakeController {
    suspend fun wakeDevice(): PlatformCapability<Unit>
}

/** No public Android 17 wake path has been verified for the locked Pixel 8 Pro baseline. */
class UnavailableDeviceWakeController : DeviceWakeController {
    override suspend fun wakeDevice(): PlatformCapability<Unit> = PlatformCapability.Unavailable(
        code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
        detail = "No lock-screen display wake operation has been verified on the target device",
    )
}
