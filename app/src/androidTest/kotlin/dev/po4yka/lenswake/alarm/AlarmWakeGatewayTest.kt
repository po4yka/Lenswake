package dev.po4yka.lenswake.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.R
import dev.po4yka.lenswake.core.SessionId
import dev.po4yka.lenswake.platform.AndroidDeviceWakeController
import dev.po4yka.lenswake.platform.PlatformCapability
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmWakeGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

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
    fun validScheduleAlarmIsForwardedWithoutChangingDurablePayload() {
        val incoming = AlarmContract.intent(context, testSchedule(), AlarmKind.START)

        val forwarded = requireNotNull(
            AlarmWakeGatewayContract.forwardedServiceIntent(context, incoming),
        )

        assertEquals(
            ComponentName(context, AutomationExecutionService::class.java),
            forwarded.component,
        )
        assertEquals(incoming.action, forwarded.action)
        assertEquals(incoming.data, forwarded.data)
        assertEquals(AlarmContract.parse(incoming), AlarmContract.parse(forwarded))
        assertEquals(0, forwarded.flags)
    }

    @Test
    fun validSessionBoundStopIsForwardedWithoutChangingDurablePayload() {
        val trigger = RehearsalStopTrigger(
            sessionId = SessionId("gateway-rehearsal"),
            expectedAt = Instant.parse("2026-08-10T05:30:00Z"),
            deliveryAttempt = 2,
        )
        val incoming = RehearsalStopAlarmContract.triggerIntent(context, trigger)

        val forwarded = requireNotNull(
            AlarmWakeGatewayContract.forwardedServiceIntent(context, incoming),
        )

        assertEquals(
            ComponentName(context, AutomationExecutionService::class.java),
            forwarded.component,
        )
        assertEquals(trigger, RehearsalStopAlarmContract.parse(forwarded))
    }

    @Test
    fun implicitWrongComponentAndMalformedAlarmsAreRejected() {
        val valid = AlarmContract.intent(context, testSchedule(), AlarmKind.STOP)

        assertNull(
            AlarmWakeGatewayContract.forwardedServiceIntent(
                context,
                Intent(valid).setComponent(null),
            ),
        )
        assertNull(
            AlarmWakeGatewayContract.forwardedServiceIntent(
                context,
                Intent(valid).setComponent(
                    ComponentName(context, AutomationExecutionService::class.java),
                ),
            ),
        )
        assertNull(
            AlarmWakeGatewayContract.forwardedServiceIntent(
                context,
                Intent(valid).replaceExtras(null),
            ),
        )
    }

    @Test
    fun wakeOnlyIsExplicitAndNeverForwardsAutomation() {
        val wakeOnly = AlarmWakeGatewayContract.wakeOnlyIntent(context)

        assertTrue(AlarmWakeGatewayContract.isWakeOnly(context, wakeOnly))
        assertNull(AlarmWakeGatewayContract.forwardedServiceIntent(context, wakeOnly))
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
    fun productionWakeControllerReportsDeclaredPrivateGateway() {
        assertTrue(
            AndroidDeviceWakeController(context).availability() is PlatformCapability.Available,
        )
    }
}
