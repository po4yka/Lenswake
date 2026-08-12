package dev.po4yka.lenswake.integration

import android.content.Context
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

internal interface PixelCameraPackageAccess {
    fun cameraVersionCode(): Long?

    fun resources(): Resources?

    fun hasSupportedSigningCertificate(): Boolean
}

/** Reads the compatibility identity Android exposes instead of relying on build-time constants. */
class AndroidPixelCameraEnvironmentProbe internal constructor(
    context: Context,
    private val packageAccess: PixelCameraPackageAccess,
) {
    constructor(context: Context) : this(
        context = context,
        packageAccess = AndroidPixelCameraPackageAccess(
            packageManager = context.applicationContext.packageManager,
            identity = SUPPORTED_PIXEL_CAMERA_IDENTITY,
        ),
    )

    private val applicationContext = context.applicationContext

    fun inspect(): PortResult<PixelCameraEnvironment> {
        val cameraIdentity = SUPPORTED_PIXEL_CAMERA_IDENTITY
        val cameraVersionCode = packageAccess.cameraVersionCode()
        val cameraResources = packageAccess.resources()
        return when {
            cameraVersionCode == null -> unavailable(
                "${cameraIdentity.packageName} is not installed or visible",
            )
            cameraResources == null -> unavailable("Pixel Camera resources are not available")
            !packageAccess.hasSupportedSigningCertificate() ->
                unavailable("Pixel Camera is not signed by the supported Google certificate")
            else -> observed(cameraVersionCode, cameraResources, cameraIdentity)
        }
    }

    private fun observed(
        cameraVersionCode: Long,
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
                cameraVersionCode = cameraVersionCode,
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

internal class AndroidPixelCameraPackageAccess(
    private val packageManager: PackageManager,
    private val identity: PixelCameraPackageIdentity,
) : PixelCameraPackageAccess {
    override fun cameraVersionCode(): Long? = try {
        packageManager.getPackageInfo(
            identity.packageName,
            PackageManager.PackageInfoFlags.of(0),
        ).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override fun resources(): Resources? = try {
        packageManager.getResourcesForApplication(identity.packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override fun hasSupportedSigningCertificate(): Boolean = packageManager.hasSigningCertificate(
        identity.packageName,
        identity.signingCertificate.toByteArray(),
        PackageManager.CERT_INPUT_SHA256,
    )
}
