package dev.po4yka.lenswake.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.platform.AndroidDeviceWakeController
import dev.po4yka.lenswake.platform.DeviceWakeNotificationContract
import dev.po4yka.lenswake.platform.PlatformCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmWakeGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun gatewayIsPrivateBoundedAndExcludedFromHistory() {
        val info = context.packageManager.getActivityInfo(
            AlarmWakeGatewayContract.component(context),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertFalse(info.exported)
        assertTrue(info.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
        assertTrue(info.taskAffinity.isNullOrEmpty())
        assertEquals(R.style.Theme_Lenswake_WakeGateway, info.themeResource)
    }

    @Test
    fun manifestDeclaresFullScreenIntentAndNotificationPermissions() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.USE_FULL_SCREEN_INTENT in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
    }

    @Test
    fun wakeOnlyIntentIsExplicitAndRejectsAdditionalPayload() {
        val wakeOnly = AlarmWakeGatewayContract.wakeOnlyIntent(context)

        assertEquals(AlarmWakeGatewayContract.component(context), wakeOnly.component)
        assertTrue(AlarmWakeGatewayContract.isWakeOnly(context, wakeOnly))
        assertFalse(
            AlarmWakeGatewayContract.isWakeOnly(
                context,
                Intent(wakeOnly).setComponent(null),
            ),
        )
        assertFalse(
            AlarmWakeGatewayContract.isWakeOnly(
                context,
                Intent(wakeOnly).putExtra("unexpected", true),
            ),
        )
    }

    @Test
    fun wakeNotificationIsUrgentAlarmWithImmutableFullScreenIntent() {
        DeviceWakeNotificationContract.ensureChannel(notificationManager)

        val notification = DeviceWakeNotificationContract.notification(context)
        val channel = requireNotNull(
            notificationManager.getNotificationChannel(DeviceWakeNotificationContract.CHANNEL_ID),
        )

        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(
            DeviceWakeNotificationContract.MAX_NOTIFICATION_LIFETIME_MILLIS,
            notification.timeoutAfter,
        )
        assertNotNull(notification.fullScreenIntent)
        assertTrue(requireNotNull(notification.fullScreenIntent).isImmutable)
        assertEquals(context.packageName, notification.fullScreenIntent?.creatorPackage)
    }

    @Test
    fun productionAvailabilityFailsClosedAgainstLiveNotificationState() {
        DeviceWakeNotificationContract.ensureChannel(notificationManager)
        val channel = notificationManager.getNotificationChannel(
            DeviceWakeNotificationContract.CHANNEL_ID,
        )
        val permissionGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val expectedAvailable = permissionGranted &&
            notificationManager.areNotificationsEnabled() &&
            notificationManager.canUseFullScreenIntent() &&
            channel != null &&
            channel.importance >= NotificationManager.IMPORTANCE_HIGH

        val actual = AndroidDeviceWakeController(context).availability()

        assertEquals(expectedAvailable, actual is PlatformCapability.Available)
    }
}
