package com.lanraragi.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-10-04 C06 / STAB-23: one trace per cause, thread name, breadcrumbs. */
class CrashReportTest {

    private fun countOf(haystack: String, needle: String) = haystack.windowed(needle.length).count { it == needle }

    @Test
    fun causes_arePrintedOnce() {
        val t = IllegalStateException("outer", IllegalArgumentException("middle", RuntimeException("root")))
        val report = Crash.buildReport("HEADER\n", "worker-1", t, listOf("01-01 I/Scene: GalleryListScene"))
        assertEquals(1, countOf(report, "java.lang.RuntimeException: root"))
        assertEquals(1, countOf(report, "java.lang.IllegalArgumentException: middle"))
        assertTrue(report.startsWith("HEADER\n"))
        assertTrue(report.contains("Thread=worker-1\n"))
        assertTrue(report.contains("I/Scene: GalleryListScene"))
    }

    /** Audit 2026-10-06 C06 / SEC-02: messages are redacted, frames are not. */
    @Test
    fun serverAddresses_inMessagesAndBreadcrumbs_areRedacted_framesKept() {
        val t = java.io.IOException(
            "Cannot resolve page path 'x' against https://lrr.example.com:3000/lrr",
            java.net.UnknownHostException("Unable to resolve host \"lrr.example.com\": No address associated with hostname"),
        )
        val report = Crash.buildReport(
            "HEADER\n", "worker-1", t,
            listOf("01-01 W/Net: failed to connect to nas.lan/192.168.1.5 (port 3000)"),
        )
        assertFalse(report, report.contains("example.com"))
        assertFalse(report, report.contains("nas.lan"))
        assertFalse(report, report.contains("192.168.1.5"))
        assertTrue(report, report.contains("against https://<host>/lrr"))
        assertTrue(report, report.contains("Unable to resolve host \"<host>\""))
        assertTrue(report, report.contains("W/Net: failed to connect to <host>/<ip> (port 3000)"))
        val frame = "\tat com.lanraragi.reader.CrashReportTest." +
            "serverAddresses_inMessagesAndBreadcrumbs_areRedacted_framesKept(CrashReportTest.kt:"
        assertTrue(report, report.contains(frame))
    }

    /**
     * Audit 2026-10-06d REL-04: a release frame names its mapping as
     * `r8-map-id-<pg_map_id>` plus a minified line; retrace needs both
     * verbatim, so the redaction must not touch them.
     */
    @Test
    fun r8Frames_keepMapIdAndLineNumber() {
        val mapId = "r8-map-id-797e10a046b0a5a6fb81376ed0d17a08ac03dcb43b27d8f917e7c8d7f288cfb6"
        val t = IllegalStateException("boom").apply {
            stackTrace = arrayOf(
                StackTraceElement("a3", "b", mapId, 42),
                StackTraceElement("zx", "invoke", mapId, 7),
            )
        }
        val report = Crash.buildReport("", "main", t, emptyList())
        assertTrue(report, report.contains("\tat a3.b($mapId:42)"))
        assertTrue(report, report.contains("\tat zx.invoke($mapId:7)"))
    }

    @Test
    fun emptyBreadcrumbs_areMarked() {
        val report = Crash.buildReport("", "main", RuntimeException("x"), emptyList())
        assertTrue(report.contains("======== Recent events ========\n(none)\n"))
    }

    @Test
    fun signature_separatesDifferentBugs_andGroupsRepeats() {
        fun make() = IllegalStateException("a")
        val (first, second) = List(2) { make() }
        assertEquals(Crash.signature(first), Crash.signature(second))
        assertNotEquals(Crash.signature(first), Crash.signature(IllegalArgumentException("a")))
    }
}
