package dev.po4yka.lenswake.application

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PixelCameraRuntimeBuildPolicyTest {
    private val environment = KnownPixelCameraProfileCatalog
        .pixel8ProAndroid17Camera69481630
        .environment

    @Test
    fun `known global stable fingerprint is accepted`() {
        assertTrue(isSupportedPixelCameraRuntime(environment))
    }

    @Test
    fun `every supported model accepts its current global stable build window`() {
        SupportedPixelModelRegistry.entries.forEach { model ->
            val buildId = when (model.globalStableBuildWindow) {
                PixelGlobalStableBuildWindow.JULY_2026 -> "CP2A.260705.006"
                PixelGlobalStableBuildWindow.JULY_AUGUST_2026 -> "CP2A.260805.005"
            }
            val candidate = environment.copy(
                deviceModel = model.model,
                deviceCodename = model.codename,
                androidBuildFingerprint =
                    "google/${model.codename}/${model.codename}:17/$buildId/1:user/release-keys",
            )

            assertTrue(isSupportedPixelCameraRuntime(candidate), model.model)
            assertTrue(KnownPixelCameraProfileCatalog.exactMatch(candidate) != null, model.model)
        }
    }

    @Test
    fun `stable-looking beta carrier and custom fingerprints are rejected`() {
        val rejected = listOf(
            environment.copy(
                deviceModel = "Pixel 7",
                deviceCodename = "panther",
                androidBuildFingerprint =
                    "google/panther_beta/panther:CinnamonBun/CP41.260701.005/15834971:user/release-keys",
            ),
            environment.copy(
                androidBuildFingerprint =
                    "google/husky/husky:17/CP2A.260705.006.A1/15641321:user/release-keys",
            ),
            environment.copy(
                androidBuildFingerprint =
                    "google/husky/husky:17/CUSTOM.260705.006/1:user/release-keys",
            ),
            environment.copy(
                androidBuildFingerprint =
                    "google/husky_beta/husky:17/CP2A.260705.006/15641320:user/release-keys",
            ),
            environment.copy(
                androidBuildFingerprint =
                    "google/husky/husky:17/CP2A.260605.012/15300000:user/release-keys",
            ),
            environment.copy(androidSdk = 36),
        )

        rejected.forEach { candidate ->
            assertFalse(isSupportedPixelCameraRuntime(candidate), candidate.androidBuildFingerprint)
            assertNull(KnownPixelCameraProfileCatalog.exactMatch(candidate))
        }
    }

    @Test
    fun `a global build from a newer device cohort is rejected on Pixel 7`() {
        val candidate = environment.copy(
            deviceModel = "Pixel 7",
            deviceCodename = "panther",
            androidBuildFingerprint =
                "google/panther/panther:17/CP2A.260805.005/1:user/release-keys",
        )

        assertFalse(isSupportedPixelCameraRuntime(candidate))
        assertNull(KnownPixelCameraProfileCatalog.exactMatch(candidate))
    }
}
