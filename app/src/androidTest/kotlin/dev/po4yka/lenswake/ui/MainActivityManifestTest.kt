package dev.po4yka.lenswake.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.po4yka.lenswake.MainActivity
import org.junit.Assert.assertEquals
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
}
