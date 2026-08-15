package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.application.KnownPixelCameraProfileCatalog
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileSource
import dev.po4yka.lenswake.core.SelectorTemplateReference
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PixelCameraProfileValidatorTest {
    @Test
    fun `stale standard template is rejected before rehearsal or dispatch`() {
        val environment = stablePixel7Environment()
        val current = checkNotNull(KnownPixelCameraProfileCatalog.exactMatch(environment))
        val stale = current.copy(
            selectorTemplate = SelectorTemplateReference("pixel-7-semantic", 2),
            compatibility = ProfileCompatibility.VERIFIED,
            verifiedAt = Instant.parse("2026-08-12T12:00:00Z"),
        )
        val validator = PixelCameraProfileValidator { PortResult.Observed(environment) }

        val failure = checkNotNull(validator.validate(ProfileUse(stale, ProfileUse.Kind.REHEARSAL)))

        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, failure.code)
    }

    @Test
    fun `authorized beta calibration profile is rejected before rehearsal or dispatch`() {
        val beta = KnownPixelCameraProfileCatalog.pixel7SemanticTemplate
        val validator = PixelCameraProfileValidator { PortResult.Observed(beta.environment) }

        val failure = checkNotNull(validator.validate(ProfileUse(beta, ProfileUse.Kind.REHEARSAL)))

        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, failure.code)
    }

    @Test
    fun `physical profile cannot bypass unsupported system build policy`() {
        val environment = KnownPixelCameraProfileCatalog
            .pixel8ProAndroid17Camera69481630
            .environment
            .copy(
                androidBuildFingerprint =
                    "google/husky/husky:17/CP2A.260705.006.A1/15641321:user/release-keys",
            )
        val profile = KnownPixelCameraProfileCatalog
            .pixel8ProAndroid17Camera69481630
            .copy(
                environment = environment,
                source = ProfileSource.PHYSICAL_TEMPLATE,
                compatibility = ProfileCompatibility.VERIFIED,
                verifiedAt = Instant.parse("2026-08-12T12:00:00Z"),
            )
        val validator = PixelCameraProfileValidator { PortResult.Observed(environment) }

        val failure = checkNotNull(
            validator.validate(ProfileUse(profile, ProfileUse.Kind.REHEARSAL)),
        )

        assertEquals(AutomationFailureCode.PROFILE_INCOMPATIBLE, failure.code)
    }

    private fun stablePixel7Environment() = KnownPixelCameraProfileCatalog
        .pixel8ProAndroid17Camera69481630
        .environment
        .copy(
            deviceModel = "Pixel 7",
            deviceCodename = "panther",
            androidBuildFingerprint =
                "google/panther/panther:17/CP2A.260705.006/15641320:user/release-keys",
            displayWidthPx = 1_080,
            displayHeightPx = 2_400,
            densityDpi = 420,
        )
}
