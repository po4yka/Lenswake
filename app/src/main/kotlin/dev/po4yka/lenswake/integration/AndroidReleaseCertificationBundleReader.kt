package dev.po4yka.lenswake.integration

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.net.toUri
import dev.po4yka.lenswake.application.CertifiedTargetReceipt
import dev.po4yka.lenswake.application.ReleaseCertificationBundleReader
import dev.po4yka.lenswake.application.ReleaseCertificationReceipt
import dev.po4yka.lenswake.application.VerifiedReleaseCertificationBundle
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.jar.JarFile

class AndroidReleaseCertificationBundleReader(
    context: Context,
    expectedReleaseCertificateSha256: String,
) : ReleaseCertificationBundleReader {
    private val applicationContext = context.applicationContext
    private val expectedCertificate = expectedReleaseCertificateSha256.lowercase().also(::requireSha256)

    override fun read(uri: String): VerifiedReleaseCertificationBundle {
        require(releaseApkIsTrusted()) { "Installed Lenswake APK is not release-signed" }
        val temporary = File.createTempFile("release-certification-", ".jar", applicationContext.cacheDir)
        return try {
            applicationContext.contentResolver.openInputStream(uri.toUri()).use { input ->
                requireNotNull(input) { "Certification bundle could not be opened" }
                temporary.outputStream().use { output ->
                    input.copyBoundedTo(output, MAX_BUNDLE_BYTES)
                }
            }
            require(temporary.length() <= MAX_BUNDLE_BYTES) { "Certification bundle is too large" }
            val bundleSha256 = temporary.sha256()
            val receiptText = verifiedReceiptText(temporary)
            val receipt = ReleaseCertificationReceiptParser.parse(receiptText)
            val installedApkSha256 = File(applicationContext.applicationInfo.sourceDir).sha256()
            require(receipt.apkSha256 == installedApkSha256) {
                "Certification receipt targets a different APK"
            }
            VerifiedReleaseCertificationBundle(receipt, bundleSha256, installedApkSha256)
        } finally {
            temporary.delete()
        }
    }

    private fun verifiedReceiptText(bundle: File): String = JarFile(bundle, true).use { jar ->
        val payloadEntries = jar.entries().asSequence()
            .filterNot { it.isDirectory || it.name.startsWith("META-INF/") }
            .toList()
        require(payloadEntries.map { it.name } == listOf(RECEIPT_ENTRY)) {
            "Certification bundle must contain only $RECEIPT_ENTRY"
        }
        val entry = payloadEntries.single()
        require(entry.size in 1..MAX_RECEIPT_BYTES) { "Certification receipt has an invalid size" }
        val bytes = jar.getInputStream(entry).use { input ->
            input.readNBytes(MAX_RECEIPT_BYTES.toInt() + 1).also { content ->
                require(content.size <= MAX_RECEIPT_BYTES) { "Certification receipt is too large" }
            }
        }
        val certificateDigests = entry.certificates.orEmpty().map { certificate ->
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .toHex()
        }
        require(expectedCertificate in certificateDigests) {
            "Certification receipt is not signed by the Lenswake release key"
        }
        bytes.toString(Charsets.UTF_8)
    }

    private fun releaseApkIsTrusted(): Boolean = applicationContext.packageManager.hasSigningCertificate(
        applicationContext.packageName,
        expectedCertificate.hexBytes(),
        PackageManager.CERT_INPUT_SHA256,
    )

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    companion object {
        const val RECEIPT_ENTRY = "PHYSICAL-ACCEPTANCE.txt"
        private const val MAX_BUNDLE_BYTES = 1024L * 1024L
        private const val MAX_RECEIPT_BYTES = 32L * 1024L
    }
}

internal object ReleaseCertificationReceiptParser {
    fun parse(text: String): ReleaseCertificationReceipt {
        val fields = text.lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Malformed certification field" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
            .toList()
        require(fields.map { it.first }.distinct().size == fields.size) {
            "Certification receipt contains duplicate fields"
        }
        val values = fields.toMap()
        require(values.keys == REQUIRED_FIELDS) { "Certification receipt fields do not match schema" }
        require(values.getValue("schemaVersion") == "2") { "Unsupported certification schema" }
        val receipt = ReleaseCertificationReceipt(
            releaseTag = values.getValue("tag"),
            releaseCommit = values.getValue("commit"),
            candidateRunId = values.getValue("candidateRunId").toLong(),
            apkFilename = values.getValue("apkFilename"),
            apkSha256 = values.getValue("apkSha256"),
            pixel7 = CertifiedTargetReceipt(
                "Pixel 7",
                "panther",
                values.getValue("pixel7ProfileFingerprint"),
                values.getValue("pixel7EvidenceUrl"),
                values.getValue("pixel7EvidenceSha256"),
            ),
            pixel8Pro = CertifiedTargetReceipt(
                "Pixel 8 Pro",
                "husky",
                values.getValue("pixel8ProProfileFingerprint"),
                values.getValue("pixel8ProEvidenceUrl"),
                values.getValue("pixel8ProEvidenceSha256"),
            ),
        )
        require(RELEASE_TAG.matches(receipt.releaseTag))
        require(GIT_COMMIT.matches(receipt.releaseCommit))
        require(receipt.candidateRunId > 0)
        require(receipt.apkFilename.matches(APK_FILENAME))
        require(receipt.pixel7.evidenceUrl.matches(HTTPS_URL))
        require(receipt.pixel8Pro.evidenceUrl.matches(HTTPS_URL))
        listOf(
            receipt.apkSha256,
            receipt.pixel7.acceptedExperimentalProfileFingerprint,
            receipt.pixel7.evidenceSha256,
            receipt.pixel8Pro.acceptedExperimentalProfileFingerprint,
            receipt.pixel8Pro.evidenceSha256,
        ).forEach(::requireSha256)
        require(receipt.pixel7.evidenceSha256 != receipt.pixel8Pro.evidenceSha256)
        return receipt
    }

    private val REQUIRED_FIELDS = setOf(
        "schemaVersion", "tag", "commit", "candidateRunId", "apkFilename", "apkSha256",
        "pixel7ProfileFingerprint", "pixel7EvidenceUrl", "pixel7EvidenceSha256",
        "pixel8ProProfileFingerprint", "pixel8ProEvidenceUrl", "pixel8ProEvidenceSha256",
    )
    private val RELEASE_TAG = Regex("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?$")
    private val GIT_COMMIT = Regex("^[0-9a-f]{40}$")
    private val APK_FILENAME = Regex("^Lenswake-[0-9A-Za-z.+-]+\\.apk$")
    private val HTTPS_URL = Regex("^https://\\S+$")
}

private fun requireSha256(value: String) {
    require(value.matches(Regex("^[0-9a-f]{64}$")))
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK)
}

private fun String.hexBytes(): ByteArray =
    chunked(HEX_BYTE_CHARACTERS).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()

private fun InputStream.copyBoundedTo(output: OutputStream, maxBytes: Long) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return
        copied += count
        require(copied <= maxBytes) { "Certification bundle is too large" }
        output.write(buffer, 0, count)
    }
}

private const val HEX_BYTE_CHARACTERS = 2
private const val HEX_RADIX = 16
private const val UNSIGNED_BYTE_MASK = 0xff
