package dev.po4yka.lenswake.platform

import dev.po4yka.lenswake.core.PixelCameraEnvironment

@JvmInline
internal value class SigningCertificateSha256(
    val hex: String,
) {
    init {
        require(hex.length == SHA256_HEX_LENGTH && hex.all { it.isLowerCaseHexDigit() }) {
            "Signing certificate SHA-256 must be 64 lowercase hexadecimal characters"
        }
    }

    fun toByteArray(): ByteArray = hex.chunked(HEX_BYTE_CHARACTER_COUNT)
        .map { it.toInt(HEX_RADIX).toByte() }
        .toByteArray()

    private fun Char.isLowerCaseHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

    private companion object {
        const val SHA256_HEX_LENGTH = 64
        const val HEX_BYTE_CHARACTER_COUNT = 2
        const val HEX_RADIX = 16
    }
}

internal data class PixelCameraPackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val signingCertificate: SigningCertificateSha256,
) {
    init {
        require(packageName.isNotBlank()) { "Camera package name must not be blank" }
        require(versionCode >= 0) { "Camera version code must not be negative" }
    }

    fun matches(environment: PixelCameraEnvironment): Boolean =
        environment.cameraPackage == packageName &&
            environment.cameraVersionCode == versionCode &&
            environment.cameraSigningCertificateSha256 == signingCertificate.hex
}

/** Exact Pixel Camera package identity accepted by the current selector templates. */
internal val SUPPORTED_PIXEL_CAMERA_IDENTITY = PixelCameraPackageIdentity(
    packageName = PIXEL_CAMERA_PACKAGE,
    versionCode = 69_481_630L,
    signingCertificate = SigningCertificateSha256(
        "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
    ),
)
