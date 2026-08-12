package dev.po4yka.lenswake.integration

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

class AndroidLenswakeArtifactIdentity(
    context: Context,
    expectedReleaseCertificateSha256: String,
) {
    private val applicationContext = context.applicationContext
    private val expectedCertificate = expectedReleaseCertificateSha256.lowercase()
    private val digest: String? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (!releaseSignatureMatches()) null else sha256(File(applicationContext.applicationInfo.sourceDir))
    }

    fun releaseApkSha256(): String? = digest

    private fun releaseSignatureMatches(): Boolean = runCatching {
        applicationContext.packageManager.hasSigningCertificate(
            applicationContext.packageName,
            expectedCertificate.chunked(HEX_BYTE_CHARACTERS).map { it.toInt(HEX_RADIX).toByte() }.toByteArray(),
            PackageManager.CERT_INPUT_SHA256,
        )
    }.getOrDefault(false)

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }
    }

    private companion object {
        const val HEX_BYTE_CHARACTERS = 2
        const val HEX_RADIX = 16
        const val UNSIGNED_BYTE_MASK = 0xff
    }
}
