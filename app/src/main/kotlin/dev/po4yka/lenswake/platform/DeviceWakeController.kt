package dev.po4yka.lenswake.platform

import android.Manifest
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.alarm.AlarmWakeGatewayContract
import kotlinx.coroutines.delay

interface DeviceWakeController {
    fun availability(): PlatformCapability<Unit>

    suspend fun wakeDevice(): PlatformCapability<Unit>
}

class UnavailableDeviceWakeController : DeviceWakeController {
    override fun availability(): PlatformCapability<Unit> = unavailable()

    override suspend fun wakeDevice(): PlatformCapability<Unit> = unavailable()

    private fun unavailable(): PlatformCapability.Unavailable = PlatformCapability.Unavailable(
        code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
        detail = "No lock-screen display wake operation has been verified on the target device",
    )
}

/**
 * Wakes a locked display through Android's alarm-category full-screen notification surface.
 *
 * The durable exact alarm continues to target [dev.po4yka.lenswake.alarm.AutomationExecutionService].
 * This controller only posts a local, immutable full-screen intent and verifies the display became
 * interactive. It never acquires a wake lock and never requests keyguard dismissal.
 */
class AndroidDeviceWakeController(context: Context) : DeviceWakeController {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val notificationManager =
        applicationContext.getSystemService(NotificationManager::class.java)
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private val gatewayComponent = AlarmWakeGatewayContract.component(applicationContext)

    override fun availability(): PlatformCapability<Unit> = try {
        when {
            !gatewayIsDeclaredPrivateAndEnabled() -> unavailable(
                "The display-wake gateway is missing, disabled, or exported",
            )

            !manifestDeclares(Manifest.permission.USE_FULL_SCREEN_INTENT) -> unavailable(
                "USE_FULL_SCREEN_INTENT is not declared",
            )

            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED -> unavailable(
                "Notification permission is not granted",
            )

            !notificationManager.areNotificationsEnabled() -> unavailable(
                "Notifications are disabled for Lenswake",
            )

            !notificationManager.canUseFullScreenIntent() -> unavailable(
                "Full-screen alarm notifications are not allowed for Lenswake",
            )

            else -> {
                DeviceWakeNotificationContract.ensureChannel(applicationContext, notificationManager)
                val channel = notificationManager.getNotificationChannel(
                    DeviceWakeNotificationContract.CHANNEL_ID,
                )
                if (channel == null || channel.importance < NotificationManager.IMPORTANCE_HIGH) {
                    unavailable("The scheduled camera wake notification channel is not urgent")
                } else {
                    PlatformCapability.Available(Unit)
                }
            }
        }
    } catch (error: PackageManager.NameNotFoundException) {
        unavailable("The private display-wake gateway is not declared", error)
    } catch (error: RuntimeException) {
        unavailable("Display-wake notification capability could not be inspected", error)
    }

    override suspend fun wakeDevice(): PlatformCapability<Unit> {
        if (powerManager.isInteractive) {
            DeviceWakeNotificationContract.cancel(notificationManager)
            return PlatformCapability.Available(Unit)
        }
        val readiness = availability()
        if (readiness is PlatformCapability.Unavailable) return readiness

        try {
            notificationManager.notify(
                DeviceWakeNotificationContract.NOTIFICATION_ID,
                DeviceWakeNotificationContract.notification(applicationContext),
            )
        } catch (error: SecurityException) {
            return unavailable("The full-screen wake notification was rejected", error)
        } catch (error: RuntimeException) {
            return unavailable("The full-screen wake notification could not be posted", error)
        }

        return try {
            repeat(WAKE_CONFIRMATION_ATTEMPTS) {
                delay(WAKE_CONFIRMATION_INTERVAL_MILLIS)
                if (powerManager.isInteractive) return PlatformCapability.Available(Unit)
            }
            unavailable("The display did not become interactive before the wake deadline")
        } finally {
            DeviceWakeNotificationContract.cancel(notificationManager)
        }
    }

    private fun gatewayIsDeclaredPrivateAndEnabled(): Boolean {
        val info = packageManager.getActivityInfo(
            gatewayComponent,
            PackageManager.ComponentInfoFlags.of(0),
        )
        val application = packageManager.getApplicationInfo(
            applicationContext.packageName,
            PackageManager.ApplicationInfoFlags.of(0),
        )
        return info.enabled && !info.exported && application.enabled
    }

    private fun manifestDeclares(permission: String): Boolean {
        val packageInfo = packageManager.getPackageInfo(
            applicationContext.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        return permission in packageInfo.requestedPermissions.orEmpty()
    }

    private fun unavailable(
        detail: String,
        cause: Throwable? = null,
    ): PlatformCapability.Unavailable = PlatformCapability.Unavailable(
        code = PlatformCapabilityCode.NO_VERIFIED_WAKE_PATH,
        detail = detail,
        cause = cause,
    )

    private companion object {
        const val WAKE_CONFIRMATION_ATTEMPTS = 100
        const val WAKE_CONFIRMATION_INTERVAL_MILLIS = 50L
    }
}

internal object DeviceWakeNotificationContract {
    const val CHANNEL_ID = "scheduled_camera_wake"
    const val NOTIFICATION_ID = 30_001
    const val MAX_NOTIFICATION_LIFETIME_MILLIS = 10_000L
    private const val PENDING_INTENT_REQUEST_CODE = 30_001

    fun ensureChannel(
        context: Context,
        notificationManager: NotificationManager,
    ) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.wake_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.wake_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    fun notification(context: Context): Notification {
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            PENDING_INTENT_REQUEST_CODE,
            AlarmWakeGatewayContract.wakeOnlyIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                    PendingIntentCreatorBackgroundActivityStartMode.resolve(),
                )
                .toBundle(),
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.wake_notification_title))
            .setContentText(context.getString(R.string.wake_notification_preparing))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(MAX_NOTIFICATION_LIFETIME_MILLIS)
            .setFullScreenIntent(fullScreenIntent, true)
            .build()
    }

    fun cancel(notificationManager: NotificationManager) {
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
    }
}
