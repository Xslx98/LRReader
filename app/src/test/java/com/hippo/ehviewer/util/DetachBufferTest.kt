package com.hippo.ehviewer.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DetachBuffer] — the deliver-or-buffer + drain primitive
 * shared by GalleryListScene's detached-window collectors.
 */
class DetachBufferTest {

    @Test
    fun deliverOrBuffer_ready_deliversImmediately() {
        val buffer = DetachBuffer<String>()
        val delivered = mutableListOf<String>()
        buffer.deliverOrBuffer("a", ready = true) { delivered.add(it) }
        assertEquals(listOf("a"), delivered)
        // Nothing buffered, so a later drain is a no-op.
        buffer.drain { delivered.add(it) }
        assertEquals(listOf("a"), delivered)
    }

    @Test
    fun deliverOrBuffer_notReady_buffersUntilDrain() {
        val buffer = DetachBuffer<String>()
        val delivered = mutableListOf<String>()
        buffer.deliverOrBuffer("a", ready = false) { delivered.add(it) }
        buffer.deliverOrBuffer("b", ready = false) { delivered.add(it) }
        assertEquals("nothing delivered while not ready", emptyList<String>(), delivered)

        buffer.drain { delivered.add(it) }
        assertEquals("drained in arrival order", listOf("a", "b"), delivered)
    }

    @Test
    fun drain_clearsBuffer_soSecondDrainIsNoOp() {
        val buffer = DetachBuffer<String>()
        val delivered = mutableListOf<String>()
        buffer.deliverOrBuffer("a", ready = false) { delivered.add(it) }
        buffer.drain { delivered.add(it) }
        buffer.drain { delivered.add(it) }
        assertEquals(listOf("a"), delivered)
    }

    @Test
    fun drain_empty_isNoOp() {
        val buffer = DetachBuffer<String>()
        var calls = 0
        buffer.drain { calls++ }
        assertEquals(0, calls)
    }
}
