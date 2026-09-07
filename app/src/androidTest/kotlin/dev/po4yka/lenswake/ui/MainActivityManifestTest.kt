package dev.po4yka.lenswake.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.MainActivity
import dev.po4yka.lenswake.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun mainActivityRequestsResizeForImeInsets() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }

    @Test
    fun declaresSavedVideoReadPermissionForPixelCameraVerification() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )

        assertTrue(
            info.requestedPermissions.orEmpty().contains(android.Manifest.permission.READ_MEDIA_VIDEO),
        )
        assertTrue(
            info.requestedPermissions.orEmpty().contains(
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
        )
    }

    @Test
    fun launcherWindowFollowsNightModeBeforeComposeDraws() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        val (dayLightBars, dayBackground) = windowAppearance(Configuration.UI_MODE_NIGHT_NO)
        val (nightLightBars, nightBackground) = windowAppearance(Configuration.UI_MODE_NIGHT_YES)

        assertEquals(R.style.Theme_Lenswake, info.themeResource)
        assertTrue(dayLightBars)
        assertFalse(nightLightBars)
        assertTrue(
            "Pre-Compose window background is $dayBackground in both night modes",
            dayBackground != nightBackground,
        )
    }

    private fun windowAppearance(nightMode: Int): Pair<Boolean, Int> {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        val theme = context.createConfigurationContext(configuration).resources.newTheme()
        theme.applyStyle(R.style.Theme_Lenswake, true)

        val bars = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar))
        val lightSystemBars = bars.getBoolean(0, false)
        bars.recycle()

        val background = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val windowBackground = background.getColor(0, 0)
        background.recycle()

        return lightSystemBars to windowBackground
    }
}
