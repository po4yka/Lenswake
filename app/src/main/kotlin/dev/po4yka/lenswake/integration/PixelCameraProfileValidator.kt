package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.core.AutomationFailure
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.PixelCameraEnvironment
import dev.po4yka.lenswake.core.PixelCameraProfile
import dev.po4yka.lenswake.core.PixelCameraSelectorSchema
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.platform.PIXEL_CAMERA_PACKAGE

internal class PixelCameraProfileValidator(
    private val environmentProbe: () -> PortResult<PixelCameraEnvironment>,
) {
    fun validate(profileUse: ProfileUse): AutomationFailure? =
        validateIdentity(profileUse.profile) ?: validateEnvironment(profileUse)

    private fun validateIdentity(profile: PixelCameraProfile): AutomationFailure? = when {
        profile.environment.cameraPackage != PIXEL_CAMERA_PACKAGE -> AutomationFailure(
            code = AutomationFailureCode.PROFILE_INCOMPATIBLE,
            message = "The profile does not target the supported Pixel Camera package",
        )
        profile.selectorSchemaVersion != PixelCameraSelectorSchema.CURRENT_VERSION ->
            AutomationFailure(
                code = AutomationFailureCode.PROFILE_INCOMPATIBLE,
                message = "The profile selector schema is not supported by this Lenswake build",
                context = mapOf(
                    "profileSchema" to profile.selectorSchemaVersion.toString(),
                    "supportedSchema" to PixelCameraSelectorSchema.CURRENT_VERSION.toString(),
                ),
            )
        else -> null
    }

    private fun validateEnvironment(profileUse: ProfileUse): AutomationFailure? =
        when (val result = environmentProbe()) {
            is PortResult.Unavailable -> result.failure
            is PortResult.Observed -> validateCompatibility(
                profileUse,
                profileUse.profile.compatibilityFor(result.value),
            )
        }

    private fun validateCompatibility(
        profileUse: ProfileUse,
        compatibility: ProfileCompatibility,
    ): AutomationFailure? = when {
        compatibility == ProfileCompatibility.INCOMPATIBLE -> AutomationFailure(
            code = AutomationFailureCode.PROFILE_INCOMPATIBLE,
            message = "The current device or Pixel Camera package is incompatible with the profile",
        )
        profileUse.kind == ProfileUse.Kind.REHEARSAL -> null
        compatibility == ProfileCompatibility.VERIFIED -> null
        else -> AutomationFailure(
            code = AutomationFailureCode.PROFILE_REQUIRES_REHEARSAL,
            message = "Unattended automation requires a profile verified for the exact current environment",
        )
    }
}
