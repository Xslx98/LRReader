package com.lanraragi.reader.ui

import android.content.Context
import android.text.Html
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The open-source licenses page (Settings -> Update & support) loads
 * [LicenseActivity.ASSET]; it once shipped without the asset and showed
 * net::ERR_FILE_NOT_FOUND. Pins that the asset is packaged, renders as text
 * in the no-WebView fallback, switches palette in night mode, and names every
 * runtime library declared in app/build.gradle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class OpenSourceLicensesAssetTest {

    private lateinit var html: String

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        html = context.assets.open(LicenseActivity.ASSET).bufferedReader().use { it.readText() }
    }

    @Test
    fun assetIsPackagedAndNonEmpty() {
        assertTrue("asset too small: ${html.length} chars", html.length > 1000)
        assertTrue(html.contains("GNU General Public License"))
        assertTrue(html.contains("Hippo Seven"))
    }

    @Test
    fun textFallbackRendersBodyWithoutStyleSheet() {
        val text = Html.fromHtml(LicenseActivity.bodyOf(html), Html.FROM_HTML_MODE_COMPACT).toString()
        assertTrue(text.contains("OkHttp"))
        assertFalse("CSS leaked into the text fallback", text.contains("var(--"))
    }

    @Test
    fun nightModeMarksTheRootElementDark() {
        assertEquals(html, LicenseActivity.themed(html, night = false))
        val dark = LicenseActivity.themed(html, night = true)
        assertTrue(dark.contains("<html class=\"dark\" lang="))
        assertEquals(html.length + " class=\"dark\"".length, dark.length)
    }

    @Test
    fun everyRuntimeLibraryIsListed() {
        val modules = catalogModules()
        val aliases = Regex("""^\s*implementation\s+libs\.([\w.]+)\s*$""", RegexOption.MULTILINE)
            .findAll(File("build.gradle").readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("no implementation deps parsed from app/build.gradle", aliases.size > 10)
        val page = html.lowercase()
        val missing = aliases.mapNotNull { alias ->
            val module = modules[alias] ?: error("libs.$alias not in libs.versions.toml")
            val group = module.substringBefore(':').lowercase()
            module.takeUnless { page.contains(group) }
        }
        assertTrue("open_source_licenses.html is missing $missing", missing.isEmpty())
    }

    /** Catalog alias (dot-normalised, as used in `libs.x.y`) -> "group:name". */
    private fun catalogModules(): Map<String, String> {
        val entry = Regex("""^([\w-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
        return entry.findAll(File("../gradle/libs.versions.toml").readText())
            .associate { it.groupValues[1].replace('-', '.') to it.groupValues[2] }
    }
}
