package dev.po4yka.lenswake.application

import dev.po4yka.lenswake.core.DisplayOrientation
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileId
import dev.po4yka.lenswake.core.ProfileSource
import dev.po4yka.lenswake.platform.SUPPORTED_PIXEL_CAMERA_IDENTITY
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

    fun isSupportedRuntime(
        model: SupportedPixelModel,
        environment: PixelCameraEnvironment,
    ): Boolean = with(environment) {
        val fingerprint = androidBuildFingerprint ?: return false
        matches(model) && hasSupportedCamera() && hasSupportedDisplayEnvironment() &&
            PixelSystemBuildPolicy.isApprovedGlobalStable(model, fingerprint)
    }

    private fun PixelCameraEnvironment.matches(model: SupportedPixelModel): Boolean =
        deviceModel == model.model && deviceCodename == model.codename &&
            androidSdk == SUPPORTED_ANDROID_SDK

    private fun PixelCameraEnvironment.hasSupportedCamera(): Boolean =
        SUPPORTED_PIXEL_CAMERA_IDENTITY.matches(this)

    private fun PixelCameraEnvironment.hasSupportedDisplayEnvironment(): Boolean {
        val locale = Locale.forLanguageTag(localeTag)
        return locale.language == "en" && locale.country == "US" &&
            orientation == DisplayOrientation.PORTRAIT && fontScale == 1f &&
            defaultDisplayConfiguration && displayWidthPx < displayHeightPx
    }

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

    private const val SUPPORTED_ANDROID_SDK = 37
    private const val PROFILE_ID_HASH_BYTES = 8
}

internal object PixelSystemBuildPolicy {
    fun isApprovedGlobalStable(
        model: SupportedPixelModel,
        fingerprint: String,
    ): Boolean {
        val match = GOOGLE_PIXEL_FINGERPRINT.matchEntire(fingerprint) ?: return false
        return match.groupValues[PRODUCT_GROUP] == model.codename &&
            match.groupValues[DEVICE_GROUP] == model.codename &&
            model.globalStableBuildSet.accepts(
                buildId = match.groupValues[BUILD_ID_GROUP],
                incremental = match.groupValues[INCREMENTAL_GROUP],
            )
    }

    private val GOOGLE_PIXEL_FINGERPRINT =
        Regex("^google/([^/]+)/([^:]+):17/([^/]+)/([0-9]+):user/release-keys$")
    private const val PRODUCT_GROUP = 1
    private const val DEVICE_GROUP = 2
    private const val BUILD_ID_GROUP = 3
    private const val INCREMENTAL_GROUP = 4
}
