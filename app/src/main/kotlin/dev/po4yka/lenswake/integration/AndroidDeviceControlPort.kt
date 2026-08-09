package dev.po4yka.lenswake.integration

import android.content.Context
import android.os.PowerManager
import dev.po4yka.lenswake.automation.ActionDispatch
import dev.po4yka.lenswake.automation.DeviceControlPort
import dev.po4yka.lenswake.automation.DeviceState
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.InteractionMethod
import dev.po4yka.lenswake.platform.DeviceWakeController
import dev.po4yka.lenswake.platform.PlatformCapability

/** Android device-state adapter. Wake remains unavailable until it is verified on the target. */
class AndroidDeviceControlPort(
    context: Context,
    private val wakeController: DeviceWakeController,
) : DeviceControlPort {
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)

    override suspend fun inspect(): PortResult<DeviceState> = PortResult.Observed(
        DeviceState(interactive = powerManager.isInteractive),
    )

    override suspend fun wake(): ActionDispatch = when (val result = wakeController.wakeDevice()) {
        is PlatformCapability.Available -> ActionDispatch.Dispatched(InteractionMethod.STANDARD_ANDROID_API)

        is PlatformCapability.Unavailable -> ActionDispatch.Rejected(
            AutomationFailure(
                code = AutomationFailureCode.WAKE_FAILED,
                message = result.detail,
                context = mapOf("platformCapability" to result.code.name),
            ),
        )
    }
}
