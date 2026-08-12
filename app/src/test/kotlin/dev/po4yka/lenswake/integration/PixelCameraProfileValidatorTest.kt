package dev.po4yka.lenswake.integration

import dev.po4yka.lenswake.automation.PortResult
import dev.po4yka.lenswake.automation.ProfileUse
import dev.po4yka.lenswake.application.KnownPixelCameraProfileCatalog
import dev.po4yka.lenswake.core.AutomationFailureCode
import dev.po4yka.lenswake.core.ProfileCompatibility
import dev.po4yka.lenswake.core.ProfileSource
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PixelCameraProfileValidatorTest {
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
}
