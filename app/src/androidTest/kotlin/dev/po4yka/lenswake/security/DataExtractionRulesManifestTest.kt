package dev.po4yka.lenswake.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class DataExtractionRulesManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sensitiveLocalHistoryIsExcludedFromCloudAndDeviceTransfer() {
        val rulesResource = dataExtractionRulesResource()
        assertNotEquals("dataExtractionRules must reference a packaged XML resource", 0, rulesResource)

        val exclusions = extractionExclusions(rulesResource)
        val expectedDomains = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )

        listOf("cloud-backup", "device-transfer").forEach { section ->
            expectedDomains.forEach { domain ->
                assertTrue(
                    "$section must exclude the complete $domain domain",
                    domain to "." in exclusions.getValue(section),
                )
            }
        }
    }

    private fun dataExtractionRulesResource(): Int {
        val parser = context.assets.openXmlResourceParser("AndroidManifest.xml")
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "application") {
                    assertFalse(
                        "allowBackup must remain disabled",
                        parser.getAttributeBooleanValue(ANDROID_NAMESPACE, "allowBackup", true),
                    )
                    return parser.getAttributeResourceValue(
                        ANDROID_NAMESPACE,
                        "dataExtractionRules",
                        0,
                    )
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        error("Packaged manifest has no application element")
    }

    private fun extractionExclusions(resourceId: Int): Map<String, Set<Pair<String, String>>> {
        val exclusions = mutableMapOf(
            "cloud-backup" to mutableSetOf<Pair<String, String>>(),
            "device-transfer" to mutableSetOf(),
        )
        val parser = context.resources.getXml(resourceId)
        var section: String? = null
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                section = extractionSection(parser, section, exclusions)
                parser.next()
            }
        } finally {
            parser.close()
        }
        return exclusions
    }

    private fun extractionSection(
        parser: XmlPullParser,
        section: String?,
        exclusions: MutableMap<String, MutableSet<Pair<String, String>>>,
    ): String? = when {
        parser.eventType == XmlPullParser.START_TAG && parser.name in exclusions -> parser.name
        parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude" -> {
            addExclusion(parser, section, exclusions)
            section
        }

        parser.eventType == XmlPullParser.END_TAG && parser.name == section -> null
        else -> section
    }

    private fun addExclusion(
        parser: XmlPullParser,
        section: String?,
        exclusions: MutableMap<String, MutableSet<Pair<String, String>>>,
    ) {
        if (section == null) return
        exclusions.getValue(section) +=
            parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
