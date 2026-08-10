package dev.po4yka.lenswake.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.po4yka.lenswake.alarm.AlarmWakeGatewayContract
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDeviceWakeControllerTest {
    @Test
    fun interactiveDisplayDoesNotRequireWakeGatewayCapability() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val initiallyInteractive = powerManager.isInteractive
        val packageManager = context.packageManager
        val gateway = AlarmWakeGatewayContract.component(context)
        val originalState = packageManager.getComponentEnabledSetting(gateway)

        try {
            if (!initiallyInteractive) setDisplayInteractive(powerManager, interactive = true)
            try {
                packageManager.setComponentEnabledSetting(
                    gateway,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
                val controller = AndroidDeviceWakeController(context)
                assertTrue(
                    "Disabled gateway must make real wake capability unavailable",
                    controller.availability() is PlatformCapability.Unavailable,
                )

                assertTrue(
                    "An already interactive display must not require the wake gateway",
                    controller.wakeDevice() is PlatformCapability.Available,
                )
            } finally {
                restoreComponentState(packageManager, gateway, originalState)
            }
        } finally {
            if (!initiallyInteractive) setDisplayInteractive(powerManager, interactive = false)
        }
    }

    private suspend fun setDisplayInteractive(
        powerManager: PowerManager,
        interactive: Boolean,
    ) {
        val keyCode = if (interactive) "KEYCODE_WAKEUP" else "KEYCODE_SLEEP"
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent $keyCode")
            .close()
        for (attempt in 1..20) {
            if (powerManager.isInteractive == interactive) break
            delay(50)
        }
        assertEquals("Display state was not restored", interactive, powerManager.isInteractive)
    }

    private fun restoreComponentState(
        packageManager: PackageManager,
        component: ComponentName,
        state: Int,
    ) {
        packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP,
        )
    }
}
