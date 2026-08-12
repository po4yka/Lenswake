package dev.po4yka.lenswake.integration

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.DisplayOrientation
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.platform.PixelCameraPackageIdentity
import dev.po4yka.lenswake.platform.SUPPORTED_PIXEL_CAMERA_IDENTITY

/** Reads the compatibility identity Android exposes instead of relying on build-time constants. */
class AndroidPixelCameraEnvironmentProbe(context: Context) {
    private val applicationContext = context.applicationContext

    fun inspect(): PortResult<PixelCameraEnvironment> {
        val packageManager = applicationContext.packageManager
        val cameraIdentity = SUPPORTED_PIXEL_CAMERA_IDENTITY
        val packageInfo = pixelCameraPackageInfo(packageManager)
        val cameraResources = pixelCameraResources(packageManager)
        return when {
            packageInfo == null -> unavailable(
                "${cameraIdentity.packageName} is not installed or visible",
            )
            cameraResources == null -> unavailable("Pixel Camera resources are not available")
            !packageManager.hasSigningCertificate(
                cameraIdentity.packageName,
                cameraIdentity.signingCertificate.toByteArray(),
                PackageManager.CERT_INPUT_SHA256,
            ) -> unavailable("Pixel Camera is not signed by the supported Google certificate")
            else -> observed(packageInfo, cameraResources, cameraIdentity)
        }
    }

    private fun pixelCameraPackageInfo(packageManager: PackageManager): PackageInfo? =
        try {
            packageManager.getPackageInfo(
                SUPPORTED_PIXEL_CAMERA_IDENTITY.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun pixelCameraResources(packageManager: PackageManager): Resources? =
        try {
            packageManager.getResourcesForApplication(SUPPORTED_PIXEL_CAMERA_IDENTITY.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun observed(
        packageInfo: PackageInfo,
        cameraResources: Resources,
        cameraIdentity: PixelCameraPackageIdentity,
    ): PortResult<PixelCameraEnvironment> {
        val metrics = applicationContext.resources.displayMetrics
        val configuration = applicationContext.resources.configuration
        val locale = cameraResources.configuration.locales[0]
        return PortResult.Observed(
            PixelCameraEnvironment(
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                deviceCodename = Build.DEVICE,
                androidSdk = Build.VERSION.SDK_INT,
                androidBuildFingerprint = Build.FINGERPRINT,
                cameraPackage = cameraIdentity.packageName,
                cameraVersionCode = packageInfo.longVersionCode,
                cameraSigningCertificateSha256 = cameraIdentity.signingCertificate.hex,
                localeTag = locale.toLanguageTag(),
                displayWidthPx = metrics.widthPixels,
                displayHeightPx = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                fontScale = configuration.fontScale,
                orientation = when (configuration.orientation) {
                    Configuration.ORIENTATION_PORTRAIT -> DisplayOrientation.PORTRAIT
                    Configuration.ORIENTATION_LANDSCAPE -> DisplayOrientation.LANDSCAPE
                    else -> return unavailable("Display orientation is unavailable")
                },
                defaultDisplayConfiguration = hasFactoryDisplayConfiguration(),
            ),
        )
    }

    private fun hasFactoryDisplayConfiguration(): Boolean = runCatching {
        Settings.Global.getString(
            applicationContext.contentResolver,
            DISPLAY_SIZE_FORCED_SETTING,
        ).isNullOrBlank() && Settings.Secure.getString(
            applicationContext.contentResolver,
            DISPLAY_DENSITY_FORCED_SETTING,
        ).isNullOrBlank()
    }.getOrDefault(false)

    private fun unavailable(message: String): PortResult.Unavailable = PortResult.Unavailable(
        AutomationFailure(
            code = AutomationFailureCode.PIXEL_CAMERA_NOT_INSTALLED,
            message = message,
        ),
    )

    private companion object {
        const val DISPLAY_SIZE_FORCED_SETTING = "display_size_forced"
        const val DISPLAY_DENSITY_FORCED_SETTING = "display_density_forced"
    }
}
