package com.lanraragi.reader.util

import android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
import android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
import android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit 2026-10-04 C12 / PERF-02: caches give memory back, and the reader cache fits the device. */
class MemoryTrimTest {

    private class Recorder : MemoryTrimmable {
        val seen = ArrayList<MemoryTrim.Action>()
        override fun onTrimMemory(action: MemoryTrim.Action) { seen += action }
    }

    private val registered = ArrayList<MemoryTrimmable>()

    @After
    fun tearDown() {
        registered.forEach { MemoryTrim.unregister(it) }
    }

    private fun <T : MemoryTrimmable> reg(t: T): T = t.also { registered += it; MemoryTrim.register(it) }

    @Test
    fun levels_mapToActions() {
        assertEquals(MemoryTrim.Action.NONE, MemoryTrim.actionFor(TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryTrim.Action.TRIM, MemoryTrim.actionFor(TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryTrim.Action.CLEAR, MemoryTrim.actionFor(TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MemoryTrim.Action.TRIM, MemoryTrim.actionFor(TRIM_MEMORY_UI_HIDDEN))
        assertEquals(MemoryTrim.Action.TRIM, MemoryTrim.actionFor(TRIM_MEMORY_BACKGROUND))
        assertEquals(MemoryTrim.Action.CLEAR, MemoryTrim.actionFor(TRIM_MEMORY_MODERATE))
        assertEquals(MemoryTrim.Action.CLEAR, MemoryTrim.actionFor(TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun dispatch_reachesEveryRegistrant_onceEach_evenIfOneThrows() {
        val a = reg(Recorder())
        reg(object : MemoryTrimmable {
            override fun onTrimMemory(action: MemoryTrim.Action) = throw IllegalStateException("boom")
        })
        val b = reg(Recorder())
        MemoryTrim.register(a) // duplicate registration is ignored

        MemoryTrim.dispatch(TRIM_MEMORY_UI_HIDDEN)
        MemoryTrim.dispatch(TRIM_MEMORY_RUNNING_MODERATE) // NONE: not dispatched

        assertEquals(listOf(MemoryTrim.Action.TRIM), a.seen)
        assertEquals(listOf(MemoryTrim.Action.TRIM), b.seen)
    }

    @Test
    fun unregister_stopsDispatch() {
        val a = reg(Recorder())
        MemoryTrim.unregister(a)
        MemoryTrim.dispatch(TRIM_MEMORY_COMPLETE)
        assertEquals(emptyList<MemoryTrim.Action>(), a.seen)
    }

    @Test
    fun readerCache_isATwelfthOfRam_within64to256Mb_and96MbOnLowRam() {
        val gb = 1024L * 1024 * 1024
        val mb = 1024 * 1024
        assertEquals(64 * mb, MemoryTrim.readerCacheBytes(gb / 4, lowRam = false))
        assertEquals((2 * gb / 12).toInt(), MemoryTrim.readerCacheBytes(2 * gb, lowRam = false))
        assertEquals(256 * mb, MemoryTrim.readerCacheBytes(3 * gb, lowRam = false))
        assertEquals(256 * mb, MemoryTrim.readerCacheBytes(12 * gb, lowRam = false))
        assertEquals(128 * mb, MemoryTrim.readerCacheBytes(3 * gb / 2, lowRam = false))
        assertEquals(96 * mb, MemoryTrim.readerCacheBytes(gb, lowRam = true))
    }
}
