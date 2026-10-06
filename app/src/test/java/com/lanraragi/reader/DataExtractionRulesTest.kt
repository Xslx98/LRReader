package com.lanraragi.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Audit 2026-10-06b REL-01 / SEC-02: allowBackup="false" does not stop
 * Android 12+ device-to-device transfer for targetSdk 31+. A transferred
 * secure store arrives without its keystore key and can never be decrypted,
 * so the merged manifest must reference extraction rules that exclude every
 * domain (shared_prefs above all) from both cloud backup and device transfer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DataExtractionRulesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestReferencesTheExtractionRules() {
        // Robolectric's package parser does not fill dataExtractionRulesRes and
        // the unit-test merged manifest lacks the app's <application> attributes,
        // so read the source manifest (Gradle runs unit tests in the module dir).
        val app = sourceManifest().getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ANDROID_NS, "dataExtractionRules"))
        assertEquals("false", app.getAttributeNS(ANDROID_NS, "allowBackup"))
        // Still the switch for Android 11 and older, which ignore the rules file.
        assertEquals("false", app.getAttributeNS(ANDROID_NS, "fullBackupContent"))
    }

    /**
     * Audit 2026-10-06d REL-03: downloads live in app-specific storage and the
     * database next to them; on Android 10+ this lets the uninstall dialog
     * offer to keep that data, so a reinstall finds the library again.
     */
    @Test
    fun uninstallOffersToKeepAppData() {
        val app = sourceManifest().getElementsByTagName("application").item(0) as Element
        assertEquals("true", app.getAttributeNS(ANDROID_NS, "hasFragileUserData"))
    }

    private fun sourceManifest(): Document {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("run from the app module dir: ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file)
    }

    @Test
    fun deviceTransferAndCloudBackupExcludeEveryDomain() {
        val excluded = parseExcludes()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val domains = excluded[section].orEmpty()
            for (domain in ALL_DOMAINS) {
                assertTrue("$section must exclude $domain: $domains", domains.contains(domain to "."))
            }
        }
        assertTrue("no include list: it would turn the excludes into an allow-list", "include" !in excluded)
    }

    /** section -> (domain, path) of every `<exclude>`; key "include" if any `<include>` exists. */
    private fun parseExcludes(): Map<String, Set<Pair<String, String>>> {
        val result = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        val parser = ctx.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> result.getOrPut(section.orEmpty()) { mutableSetOf() }
                    .add(parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path"))
                "include" -> result.getOrPut("include") { mutableSetOf() }
            }
        }
        return result
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        val ALL_DOMAINS = listOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
    }
}
