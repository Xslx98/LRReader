package com.lanraragi.reader

import org.junit.Assert.assertEquals
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
