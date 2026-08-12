package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.DisplayOrientation
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfileSource
import java.security.MessageDigest
import java.util.Locale

internal object PixelCameraProfileTemplateFactory {
    fun derivedProfile(
        model: SupportedPixelModel,
        environment: PixelCameraEnvironment,
        template: PixelCameraProfile,
    ): PixelCameraProfile {
        return template.copy(
            id = ProfileId(exactProfileId(environment)),
            environment = environment,
            supportTier = model.supportTier,
            source = ProfileSource.EXACT_ENVIRONMENT_DERIVATION,
            selectorTemplate = model.template.reference,
            fallbackGestures = emptyMap(),
            compatibility = ProfileCompatibility.NEEDS_REHEARSAL,
            verifiedAt = null,
        )
    }

    fun isSupportedRuntime(environment: PixelCameraEnvironment): Boolean = with(environment) {
        val locale = Locale.forLanguageTag(localeTag)
        val fingerprint = androidBuildFingerprint ?: return false
        cameraPackage == PIXEL_CAMERA_PACKAGE &&
            cameraVersionCode == SUPPORTED_CAMERA_VERSION_CODE &&
            cameraSigningCertificateSha256 == GOOGLE_CAMERA_CERTIFICATE_SHA256 &&
            locale.language == "en" && locale.country == "US" &&
            orientation == DisplayOrientation.PORTRAIT &&
            fontScale == 1f && defaultDisplayConfiguration && displayWidthPx < displayHeightPx &&
            fingerprint.isSupportedGoogleBuild(deviceCodename)
    }

    private fun String.isSupportedGoogleBuild(deviceCodename: String): Boolean =
        startsWith("google/") &&
        contains("/$deviceCodename:") &&
        endsWith(":user/release-keys") &&
        !contains("_beta/") &&
        !contains("dev-keys") &&
        !contains("test-keys")

    private fun exactProfileId(environment: PixelCameraEnvironment): String {
        val identity = listOf(
            environment.deviceModel,
            environment.deviceCodename,
            environment.androidSdk,
            environment.androidBuildFingerprint,
            environment.cameraVersionCode,
            environment.cameraSigningCertificateSha256,
            environment.localeTag,
            environment.displayWidthPx,
            environment.displayHeightPx,
            environment.densityDpi,
            environment.fontScale,
            environment.orientation,
            environment.defaultDisplayConfiguration,
            PixelCameraSelectorSchema.CURRENT_VERSION,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .take(PROFILE_ID_HASH_BYTES)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "pixel-${environment.deviceCodename}-exact-v${PixelCameraSelectorSchema.CURRENT_VERSION}-$digest"
    }

    private const val PIXEL_CAMERA_PACKAGE = "com.google.android.GoogleCamera"
    private const val SUPPORTED_CAMERA_VERSION_CODE = 69_481_630L
    private const val GOOGLE_CAMERA_CERTIFICATE_SHA256 =
        "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
    private const val PROFILE_ID_HASH_BYTES = 8
}
