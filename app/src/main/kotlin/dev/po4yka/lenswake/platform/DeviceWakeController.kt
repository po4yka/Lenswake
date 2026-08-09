package dev.po4yka.lenswake.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import dev.po4yka.lenswake.alarm.AlarmWakeGatewayContract
import kotlinx.coroutines.delay

interface DeviceWakeController {
    fun availability(): PlatformCapability<Unit>

    suspend fun wakeDevice(): PlatformCapability<Unit>
}

class UnavailableDeviceWakeController : DeviceWakeController {
    override fun availability(): PlatformCapability<Unit> = PlatformCapability.Unavailable(
        code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
        detail = "No lock-screen display wake operation has been verified on the target device",
    )

    override suspend fun wakeDevice(): PlatformCapability<Unit> = PlatformCapability.Unavailable(
        code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
        detail = "No lock-screen display wake operation has been verified on the target device",
    )
}

/**
 * Wakes the display through a short-lived, lock-screen-visible Activity.
 *
 * The controller never acquires a wake lock and never dismisses the keyguard. Alarm delivery normally
 * enters through the same gateway Activity, so [wakeDevice] is an idempotent confirmation by the time
 * the automation engine runs. WAKE_ONLY is retained for bounded in-process recovery calls.
 */
class AndroidDeviceWakeController(context: Context) : DeviceWakeController {
    private val applicationContext = context.applicationContext
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private val gatewayComponent = AlarmWakeGatewayContract.component(applicationContext)

    override fun availability(): PlatformCapability<Unit> = try {
        val info = applicationContext.packageManager.getActivityInfo(
            gatewayComponent,
            PackageManager.ComponentInfoFlags.of(0),
        )
        if (!info.enabled || info.exported || !applicationContext.packageManager.isApplicationEnabled) {
            PlatformCapability.Unavailable(
                code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
                detail = "The display-wake gateway is disabled or not private",
            )
        } else {
            PlatformCapability.Available(Unit)
        }
    } catch (error: PackageManager.NameNotFoundException) {
        PlatformCapability.Unavailable(
            code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
            detail = "The private display-wake gateway is not declared",
            cause = error,
        )
    } catch (error: RuntimeException) {
        PlatformCapability.Unavailable(
            code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
            detail = "The private display-wake gateway could not be inspected",
            cause = error,
        )
    }

    override suspend fun wakeDevice(): PlatformCapability<Unit> {
        val readiness = availability()
        if (readiness is PlatformCapability.Unavailable) return readiness
        if (powerManager.isInteractive) return PlatformCapability.Available(Unit)

        try {
            applicationContext.startActivity(
                AlarmWakeGatewayContract.wakeOnlyIntent(applicationContext),
            )
        } catch (error: ActivityNotFoundException) {
            return dispatchFailure(error)
        } catch (error: SecurityException) {
            return dispatchFailure(error)
        } catch (error: RuntimeException) {
            return dispatchFailure(error)
        }

        repeat(WAKE_CONFIRMATION_ATTEMPTS) {
            delay(WAKE_CONFIRMATION_INTERVAL_MILLIS)
            if (powerManager.isInteractive) return PlatformCapability.Available(Unit)
        }
        return PlatformCapability.Unavailable(
            code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
            detail = "The display did not become interactive before the wake deadline",
        )
    }

    private fun dispatchFailure(error: RuntimeException): PlatformCapability.Unavailable =
        PlatformCapability.Unavailable(
            code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
            detail = "The private display-wake gateway could not be launched",
            cause = error,
        )

    private val PackageManager.isApplicationEnabled: Boolean
        get() = getApplicationInfo(
            applicationContext.packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        ).enabled

    private companion object {
        const val WAKE_CONFIRMATION_ATTEMPTS = 40
        const val WAKE_CONFIRMATION_INTERVAL_MILLIS = 50L
    }
}
