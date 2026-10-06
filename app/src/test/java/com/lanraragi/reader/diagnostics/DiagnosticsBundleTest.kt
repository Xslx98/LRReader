package com.lanraragi.reader.diagnostics

import com.lanraragi.reader.download.DownloadFailureReason
import com.lanraragi.reader.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter
import java.io.Writer
import java.util.zip.ZipFile

/** Audit 2026-10-04 C06 (R2): what the shared zip contains, and what it never contains. */
class DiagnosticsBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entries(zip: File): Map<String, String> = ZipFile(zip).use { z ->
        z.entries().toList().associate { e -> e.name to z.getInputStream(e).readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun zip_containsEverySection_andTheReports() {
        val report = tmp.newFile("crash-20261004-120000-000.log").apply { writeText("trace") }
        val target = tmp.root.resolve("out/diag.zip")
        DiagnosticsBundle(object : DiagnosticsBundle.Sources {
            override fun info() = "INFO"
            override fun settings(): Map<String, *> = mapOf("theme" to "dark")
            override fun events() = listOf("e1", "e2")
            override fun reports() = listOf(report)
            override fun writeLogcat(out: Writer): Boolean { out.write("L1\n"); return true }
        }).writeTo(target)

        val e = entries(target)
        assertEquals(
            setOf("info.txt", "settings.txt", "events.txt", "logcat.txt", "reports/crash-20261004-120000-000.log"),
            e.keys,
        )
        assertEquals("INFO", e["info.txt"])
        assertEquals("theme=dark\n", e["settings.txt"])
        assertEquals("e1\ne2\n", e["events.txt"])
        assertEquals("L1\n", e["logcat.txt"])
        assertEquals("trace", e["reports/crash-20261004-120000-000.log"])
    }

    /** Audit 2026-10-06 C06: a report written by an older build is redacted on the way into the zip. */
    @Test
    fun reports_areRedactedWhenBundled_framesKept() {
        val legacy = tmp.newFile("nonfatal-20261001-090000-000.log").apply {
            writeText(
                "======== CrashInfo ========\n" +
                    "java.net.ConnectException: Failed to connect to lrr.example.com/203.0.113.5:3000\n" +
                    "\tat okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:298)\n" +
                    "======== Recent events ========\n" +
                    "01-01 E/Api: GET http://10.0.0.2:3000/api/info?key=abc failed\n"
            )
        }
        val target = tmp.root.resolve("diag.zip")
        DiagnosticsBundle(object : DiagnosticsBundle.Sources {
            override fun info() = ""
            override fun settings(): Map<String, *> = emptyMap<String, Any>()
            override fun events() = emptyList<String>()
            override fun reports() = listOf(legacy)
            override fun writeLogcat(out: Writer) = false
        }).writeTo(target)
        assertEquals(
            "======== CrashInfo ========\n" +
                "java.net.ConnectException: Failed to connect to <host>/<ip>\n" +
                "\tat okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:298)\n" +
                "======== Recent events ========\n" +
                "01-01 E/Api: GET http://<host>/api/info?key=<redacted> failed\n",
            entries(target)["reports/nonfatal-20261001-090000-000.log"],
        )
    }

    @Test
    fun failedLogcat_isMarked() {
        val target = tmp.root.resolve("diag.zip")
        DiagnosticsBundle(object : DiagnosticsBundle.Sources {
            override fun info() = ""
            override fun settings(): Map<String, *> = emptyMap<String, Any>()
            override fun events() = emptyList<String>()
            override fun reports() = emptyList<File>()
            override fun writeLogcat(out: Writer) = false
        }).writeTo(target)
        assertEquals("(logcat unavailable)\n", entries(target)["logcat.txt"])
    }

    @Test
    fun renderSettings_dropsSensitiveKeys_andFreeText() {
        val out = DiagnosticsBundle.renderSettings(
            mapOf(
                "theme" to "dark",
                "read_cache_size" to "160",
                "download_delay" to 0,
                "media_scan" to true,
                "image_path" to "/storage/emulated/0/LRR",
                "image_authority" to "com.android.externalstorage.documents",
                "default_download_label" to "Manga",
                "lrr_server_url" to "http://10.0.0.2:3000",
                "api_key" to "abc",
                "excluded_ns:http://10.0.0.2:3000" to "x",
                "app_language" to "zh-CN",
                "some_free_text" to "hello world, this is me",
                "string_set" to setOf("a", "b"),
            )
        )
        assertEquals(
            "app_language=zh-CN\n" +
                "download_delay=0\n" +
                "media_scan=true\n" +
                "read_cache_size=160\n" +
                "some_free_text=<text>\n" +
                "string_set=<set of 2>\n" +
                "theme=dark\n",
            out,
        )
    }

    @Test
    fun info_render_neverShowsTheServerAddress() {
        val text = DiagnosticsInfo(
            app = listOf("VersionName" to "1.28.0"),
            device = listOf("SDK" to "35"),
            databaseVersion = 31,
            crashLogEnabled = true,
            reportCount = 2,
            serverUrl = "http://192.168.1.20:3000",
            serverInfo = "version=0.9.50",
            downloads = DiagnosticsInfo.DownloadSummary(
                byState = mapOf(DownloadState.FINISH to 3, DownloadState.FAILED to 2),
                failures = mapOf(DownloadFailureReason.NO_SPACE to 1, null to 1),
                labels = 1,
            ),
        ).render()
        assertFalse(text, text.contains("192.168"))
        assertTrue(text, text.contains("ActiveServer=scheme=http, lan=true\n"))
        assertTrue(text, text.contains("Info=version=0.9.50\n"))
        assertTrue(text, text.contains("DatabaseVersion=31\n"))
        assertTrue(text, text.contains("FINISH=3\n"))
        assertTrue(text, text.contains("WAIT=0\n"))
        assertTrue(text, text.contains("Failed.NO_SPACE=1\n"))
        assertTrue(text, text.contains("Failed.UNKNOWN=1\n"))
        assertFalse(text, text.contains("INVALID"))
    }

    @Test
    fun logcatDump_redactsAndReportsFailure() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val out = StringWriter()
        val ok = LogcatDump.writeTo(out, listOf(java, "-Dlrr.test=http://10.9.8.7:3000/api", "-XshowSettings:properties", "-version"))
        assertTrue(ok)
        assertTrue(out.toString(), out.toString().contains("lrr.test = http://<host>/api"))
        assertFalse(out.toString().contains("10.9.8.7"))

        assertFalse(LogcatDump.writeTo(StringWriter(), listOf("definitely-not-a-binary-${System.nanoTime()}")))
    }

    @Test
    fun logcatCommand_isLimitedToOurPid() {
        assertEquals(
            listOf("logcat", "-d", "-v", "threadtime", "-t", "5000", "--pid=42"),
            LogcatDump.command(42),
        )
    }
}
