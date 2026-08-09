package dev.po4yka.lenswake.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore

const val PIXEL_CAMERA_PACKAGE: String = "com.google.android.GoogleCamera"

data class ResolvedSecureCamera(
    val component: ComponentName,
    val intent: Intent,
)

class SecurePixelCameraResolver(
    private val context: Context,
) {
    fun resolve(): PlatformCapability<ResolvedSecureCamera> {
        val packageManager = context.packageManager
        if (!isPixelCameraInstalled(packageManager)) {
            return PlatformCapability.Unavailable(
                code = PlatformCapabilityCode.PIXEL_CAMERA_NOT_INSTALLED,
                detail = "$PIXEL_CAMERA_PACKAGE is not installed or visible",
            )
        }

        val implicitIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
            .setPackage(PIXEL_CAMERA_PACKAGE)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val resolveInfo = packageManager.resolveActivity(
            implicitIntent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        ) ?: return PlatformCapability.Unavailable(
            code = PlatformCapabilityCode.SECURE_CAMERA_NOT_RESOLVABLE,
            detail = "No secure camera activity resolved in $PIXEL_CAMERA_PACKAGE",
        )

        val activityInfo = resolveInfo.activityInfo
        if (activityInfo.packageName != PIXEL_CAMERA_PACKAGE) {
            return PlatformCapability.Unavailable(
                code = PlatformCapabilityCode.RESOLVED_ACTIVITY_WRONG_PACKAGE,
                detail = "Secure camera resolved to ${activityInfo.packageName}",
            )
        }
        if (!activityInfo.exported || !activityInfo.enabled) {
            return PlatformCapability.Unavailable(
                code = PlatformCapabilityCode.RESOLVED_ACTIVITY_NOT_EXPORTED,
                detail = "Resolved secure camera activity is not available to Lenswake",
            )
        }

        val component = ComponentName(activityInfo.packageName, activityInfo.name)
        return PlatformCapability.Available(
            ResolvedSecureCamera(
                component = component,
                intent = Intent(implicitIntent)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ),
        )
    }

    private fun isPixelCameraInstalled(packageManager: PackageManager): Boolean = runCatching {
        packageManager.getPackageInfo(
            PIXEL_CAMERA_PACKAGE,
            PackageManager.PackageInfoFlags.of(0),
        )
    }.isSuccess
}

sealed interface CameraLaunchDispatch {
    data class Dispatched(val component: ComponentName) : CameraLaunchDispatch

    data class Unavailable(val capability: PlatformCapability.Unavailable) : CameraLaunchDispatch
}

/**
 * Dispatches the secure camera intent. A [CameraLaunchDispatch.Dispatched] result only means that
 * Android accepted the launch request; camera visibility and state must be verified separately.
 */
class SecurePixelCameraLauncher(
    private val context: Context,
    private val resolver: SecurePixelCameraResolver = SecurePixelCameraResolver(context),
) {
    fun dispatch(): CameraLaunchDispatch = when (val resolution = resolver.resolve()) {
        is PlatformCapability.Unavailable -> CameraLaunchDispatch.Unavailable(resolution)
        is PlatformCapability.Available -> {
            try {
                context.startActivity(resolution.value.intent)
                CameraLaunchDispatch.Dispatched(resolution.value.component)
            } catch (error: SecurityException) {
                CameraLaunchDispatch.Unavailable(
                    PlatformCapability.Unavailable(
                        code = PlatformCapabilityCode.SECURE_CAMERA_DISPATCH_REJECTED,
                        detail = "Android rejected the secure camera launch request",
                        cause = error,
                    ),
                )
            } catch (error: RuntimeException) {
                CameraLaunchDispatch.Unavailable(
                    PlatformCapability.Unavailable(
                        code = PlatformCapabilityCode.SECURE_CAMERA_DISPATCH_FAILED,
                        detail = "Secure camera launch request failed",
                        cause = error,
                    ),
                )
            }
        }
    }
}
