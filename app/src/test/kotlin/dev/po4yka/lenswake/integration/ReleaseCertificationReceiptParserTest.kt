package dev.po4yka.lenswake.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReleaseCertificationReceiptParserTest {
    @Test
    fun `schema two receipt parses exact target identities`() {
        val receipt = ReleaseCertificationReceiptParser.parse(validReceipt())

        assertEquals("v1.2.3", receipt.releaseTag)
        assertEquals("panther", receipt.pixel7.codename)
        assertEquals("husky", receipt.pixel8Pro.codename)
        assertEquals("6".repeat(64), receipt.pixel8Pro.acceptedExperimentalProfileFingerprint)
    }

    @Test
    fun `unknown duplicate and malformed fields fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseCertificationReceiptParser.parse(validReceipt() + "unknown=value\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseCertificationReceiptParser.parse(validReceipt() + "apkSha256=${"2".repeat(64)}\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseCertificationReceiptParser.parse(validReceipt().replace("schemaVersion=2", "schemaVersion=1"))
        }
    }

    private fun validReceipt() = """
        schemaVersion=2
        tag=v1.2.3
        commit=${"1".repeat(40)}
        candidateRunId=123
        apkFilename=Lenswake-1.2.3.apk
        apkSha256=${"2".repeat(64)}
        pixel7EvidenceUrl=https://example.invalid/pixel-7
        pixel7EvidenceSha256=${"3".repeat(64)}
        pixel7ProfileFingerprint=${"4".repeat(64)}
        pixel8ProEvidenceUrl=https://example.invalid/pixel-8-pro
        pixel8ProEvidenceSha256=${"5".repeat(64)}
        pixel8ProProfileFingerprint=${"6".repeat(64)}
    """.trimIndent() + "\n"
}
