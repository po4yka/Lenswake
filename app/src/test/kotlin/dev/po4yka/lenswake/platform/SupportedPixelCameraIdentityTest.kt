package dev.po4yka.lenswake.platform

import dev.po4yka.lenswake.core.PixelCameraEnvironment
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportedPixelCameraIdentityTest {
    @Test
    fun `supported identity matches package version and signer as one contract`() {
        val environment = environment()

        assertTrue(SUPPORTED_PIXEL_CAMERA_IDENTITY.matches(environment))
        assertFalse(
            SUPPORTED_PIXEL_CAMERA_IDENTITY.matches(
                environment.copy(cameraPackage = "example.camera"),
            ),
        )
        assertFalse(
            SUPPORTED_PIXEL_CAMERA_IDENTITY.matches(
                environment.copy(cameraVersionCode = environment.cameraVersionCode + 1),
            ),
        )
        assertFalse(
            SUPPORTED_PIXEL_CAMERA_IDENTITY.matches(
                environment.copy(cameraSigningCertificateSha256 = "0".repeat(64)),
            ),
        )
    }

    @Test
    fun `signing identity validates and decodes its SHA-256 digest`() {
        val digest = SUPPORTED_PIXEL_CAMERA_IDENTITY.signingCertificate

        assertArrayEquals(
            digest.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            digest.toByteArray(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SigningCertificateSha256("not-a-sha256")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SigningCertificateSha256("A".repeat(64))
        }
    }

    private fun environment() = PixelCameraEnvironment(
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8 Pro",
        deviceCodename = "husky",
        androidSdk = 37,
        androidBuildFingerprint =
            "google/husky/husky:17/CP2A.260705.006/15641320:user/release-keys",
        cameraPackage = SUPPORTED_PIXEL_CAMERA_IDENTITY.packageName,
        cameraVersionCode = SUPPORTED_PIXEL_CAMERA_IDENTITY.versionCode,
        cameraSigningCertificateSha256 =
            SUPPORTED_PIXEL_CAMERA_IDENTITY.signingCertificate.hex,
        localeTag = "en-US",
        displayWidthPx = 1_008,
        displayHeightPx = 2_244,
        densityDpi = 360,
    )
}
