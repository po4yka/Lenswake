package dev.po4yka.lenswake.integration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.platform.PIXEL_CAMERA_PACKAGE

/** Reads the compatibility identity Android exposes instead of relying on build-time constants. */
class AndroidPixelCameraEnvironmentProbe(context: Context) {
    private val applicationContext = context.applicationContext

    fun inspect(): PortResult<PixelCameraEnvironment> {
        val packageManager = applicationContext.packageManager
        val packageInfo = try {
            packageManager.getPackageInfo(
                PIXEL_CAMERA_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return PortResult.Unavailable(
                AutomationFailure(
                    code = AutomationFailureCode.PIXEL_CAMERA_NOT_INSTALLED,
                    message = "$PIXEL_CAMERA_PACKAGE is not installed or visible",
                ),
            )
        }
        val cameraResources = try {
            packageManager.getResourcesForApplication(PIXEL_CAMERA_PACKAGE)
        } catch (_: PackageManager.NameNotFoundException) {
            return PortResult.Unavailable(
                AutomationFailure(
                    code = AutomationFailureCode.PIXEL_CAMERA_NOT_INSTALLED,
                    message = "Pixel Camera resources are not available",
                ),
            )
        }
        val metrics = applicationContext.resources.displayMetrics
        val locale = cameraResources.configuration.locales[0]
        return PortResult.Observed(
            PixelCameraEnvironment(
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidSdk = Build.VERSION.SDK_INT,
                androidBuildFingerprint = Build.FINGERPRINT,
                cameraPackage = PIXEL_CAMERA_PACKAGE,
                cameraVersionCode = packageInfo.longVersionCode,
                localeTag = locale.toLanguageTag(),
                displayWidthPx = metrics.widthPixels,
                displayHeightPx = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
            ),
        )
    }
}
