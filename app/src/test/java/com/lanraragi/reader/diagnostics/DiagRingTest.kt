package com.lanraragi.reader.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-10-04 C06 (R3): bounded, redacted, in-memory event buffer. */
class DiagRingTest {

    @Test
    fun keepsOnlyTheNewestCapacityLines() {
        val ring = DiagRing(capacity = 3, clock = { 0L })
        repeat(5) { ring.add('I', "T", "event $it") }
        val lines = ring.snapshot()
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].endsWith("I/T: event 2"))
        assertTrue(lines[2], lines[2].endsWith("I/T: event 4"))
    }

    @Test
    fun tail_returnsTheLastLines() {
        val ring = DiagRing(capacity = 10, clock = { 0L })
        repeat(4) { ring.add('W', "T", "e$it") }
        assertEquals(listOf("e2", "e3"), ring.tail(2).map { it.substringAfter("W/T: ") })
    }

    @Test
    fun linesAreRedacted_includingThrowableMessages() {
        val ring = DiagRing(capacity = 10, clock = { 0L })
        ring.add(
            'E', "Net", "GET http://192.168.1.5:3000/api/info?key=abc",
            IllegalStateException("outer", java.io.IOException("connect to 10.0.0.2 failed")),
        )
        val line = ring.snapshot().single()
        assertFalse(line, line.contains("192.168.1.5"))
        assertFalse(line, line.contains("10.0.0.2"))
        assertFalse(line, line.contains("abc"))
        assertTrue(line, line.contains("java.lang.IllegalStateException: outer"))
        assertTrue(line, line.contains("<- java.io.IOException"))
    }

    @Test
    fun longMessages_areTruncated() {
        val ring = DiagRing(capacity = 1, clock = { 0L })
        ring.add('I', "T", "x".repeat(5000))
        assertTrue(ring.snapshot().single().length < 700)
    }
}
