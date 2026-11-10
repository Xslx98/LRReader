package com.lanraragi.reader.reading

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Header-only page size reads for two-page pairing (item 20). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageAspectProbeTest {

    private val recorded = HashMap<Int, Float>()
    private val opened = ArrayList<Int>()
    private val probe = PageAspectProbe({ it in recorded }) { index, w, h -> recorded[index] = w.toFloat() / h }

    /** Pages on disk; a page missing from the map is not on disk. */
    private val disk = HashMap<Int, ByteArray>()
    private val open: (Int) -> InputStream? = { index ->
        opened += index
        disk[index]?.let(::ByteArrayInputStream)
    }
    private val always: () -> Boolean = { true }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun probeAround_readsTheBoundedWindowAheadAndBehind() {
        val portrait = png(70, 100)
        for (p in 0 until 200) disk[p] = portrait
        disk[103] = png(140, 100)

        probe.probeAround(100, 200, open, always)

        val ahead = (101..100 + PageAspectProbe.AHEAD).toSet()
        val behind = (100 - PageAspectProbe.BEHIND until 100).toSet()
        assertEquals(ahead + behind, recorded.keys)
        assertEquals(1.4f, recorded.getValue(103), 1e-4f)
        assertEquals(0.7f, recorded.getValue(36), 1e-4f)
    }

    @Test
    fun probeAround_stopsAtTheBookEdges() {
        val portrait = png(70, 100)
        for (p in 0 until 6) disk[p] = portrait

        probe.probeAround(2, 6, open, always)

        assertEquals(setOf(0, 1, 3, 4, 5), recorded.keys)
        assertTrue("no read past either end", opened.all { it in 0 until 6 })
    }

    @Test
    fun eachPageIsReadOnce_andKnownPagesAreNotRead() {
        val portrait = png(70, 100)
        for (p in 0 until 20) disk[p] = portrait
        recorded[9] = 0.5f

        probe.probeAround(10, 20, open, always)
        probe.probeAround(10, 20, open, always)
        probe.probeAround(12, 20, open, always)

        assertEquals("page 9 was known", 0.5f, recorded.getValue(9), 0f)
        assertEquals(opened.toSet().size, opened.size)
        assertTrue(9 !in opened)
    }

    @Test
    fun missingAhead_isReadOnceDownloaded_missingBehindIsNotLookedForAgain() {
        probe.probeAround(10, 20, open, always)
        val portrait = png(70, 100)
        disk[11] = portrait
        disk[9] = portrait
        opened.clear()

        // The preload downloaded 11; 9 appeared behind (read pages decode instead).
        probe.probe(11, open)
        probe.probeBehind(10, open, always)

        assertEquals(setOf(11), recorded.keys)
        assertTrue(9 !in opened)
    }

    @Test
    fun onlyTheHeaderIsRead() {
        // Cut inside the pixel data: a decode could not finish, the size is still known.
        val full = png(300, 200)
        disk[0] = full.copyOf(PNG_SIGNATURE_AND_IHDR + (full.size - PNG_SIGNATURE_AND_IHDR) / 2)
        disk[1] = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        probe.probe(0, open)
        probe.probe(1, open)

        assertEquals(setOf(0), recorded.keys)
        assertEquals(1.5f, recorded.getValue(0), 1e-4f)
    }

    @Test
    fun stopsWhenNoLongerActive() {
        val portrait = png(70, 100)
        for (p in 0 until 200) disk[p] = portrait
        var reads = 0
        probe.probeAround(100, 200, { opened += it; reads++; disk[it]?.let(::ByteArrayInputStream) }, { reads < 3 })
        assertEquals(3, recorded.size)
    }

    private companion object {
        /** 8-byte signature + IHDR chunk (4 length + 4 type + 13 data + 4 CRC). */
        const val PNG_SIGNATURE_AND_IHDR = 33
    }
}
