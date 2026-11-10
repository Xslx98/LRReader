package com.lanraragi.reader.reading

import android.graphics.BitmapFactory
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Learns page sizes from page files that are already on disk, before the
 * reader decodes them, so two-page reading can keep a wide page alone instead
 * of squeezing it into half a spread (item 20).
 *
 * Only the image header is read (`inJustDecodeBounds`): no pixels are decoded
 * and nothing is sampled. Pages behind the reader are probed too, so the
 * spreads behind a resumed page pair as they did when they were read. Each
 * page is read at most once per session; a page behind the reader that is not
 * on disk is not looked for again, since reading it decodes it and the decode
 * records its size.
 *
 * Runs on IO threads only, never on the render thread.
 *
 * @param isKnown whether the provider already knows the page's aspect.
 * @param record hands a page's header size to the provider.
 */
internal class PageAspectProbe(
    private val isKnown: (Int) -> Boolean,
    private val record: (index: Int, width: Int, height: Int) -> Unit,
) {
    private val settled: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * Read the header of page [index] if its aspect is not known yet.
     * [open] returns the page file's stream, or null when it is not on disk.
     * A page that is not on disk stays eligible, for a preload to fetch it.
     */
    fun probe(index: Int, open: (Int) -> InputStream?) {
        probe(index, open, rememberMissing = false)
    }

    /**
     * Probe the [AHEAD] pages after [current] (of [size]) and then the
     * [BEHIND] pages before it, nearest first, until [active] turns false.
     */
    fun probeAround(current: Int, size: Int, open: (Int) -> InputStream?, active: () -> Boolean) {
        for (i in 1..AHEAD) {
            val index = current + i
            if (index >= size || !active()) break
            probe(index, open, rememberMissing = false)
        }
        probeBehind(current, open, active)
    }

    /** Probe the [BEHIND] pages before [current], nearest first, until [active] turns false. */
    fun probeBehind(current: Int, open: (Int) -> InputStream?, active: () -> Boolean) {
        for (i in 1..BEHIND) {
            val index = current - i
            if (index < 0 || !active()) break
            probe(index, open, rememberMissing = true)
        }
    }

    private fun probe(index: Int, open: (Int) -> InputStream?, rememberMissing: Boolean) {
        if (index in settled || isKnown(index)) return
        val input = try {
            open(index)
        } catch (ignored: IOException) {
            null
        } catch (ignored: SecurityException) {
            // A revoked SAF grant on a download dir: the decode reports it.
            null
        }
        if (input == null) {
            if (rememberMissing) settled += index
            return
        }
        // Claim before reading so concurrent callers read each page once.
        if (!settled.add(index)) {
            input.closeQuietly()
            return
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            input.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (ignored: IOException) {
            // The page stays unknown; its decode reports any real problem.
            return
        }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            record(index, bounds.outWidth, bounds.outHeight)
        }
    }

    private fun InputStream.closeQuietly() {
        try {
            close()
        } catch (ignored: IOException) {
            // Nothing was read from it.
        }
    }

    companion object {
        /** Pages ahead: the online reader's preload window. */
        const val AHEAD = 5

        /**
         * Pages behind. Bounded so that opening a long book far in reads a
         * handful of headers, not the whole book; pairing further back falls
         * back to the stored parity.
         */
        const val BEHIND = 64
    }
}
