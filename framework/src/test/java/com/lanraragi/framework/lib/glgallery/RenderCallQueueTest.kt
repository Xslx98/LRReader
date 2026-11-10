package com.lanraragi.framework.lib.glgallery

import com.lanraragi.framework.util.minAllocatedBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderCallQueueTest {

    private val queue = RenderCallQueue()

    @Test
    fun drainReturnsEveryCallInOrderWithItsArguments() {
        // 40 calls: more than the initial capacity, so the arrays grow mid-batch.
        for (i in 0 until 10) {
            queue.post(1)
            queue.post(2, 1000 + i)
            queue.post(3, 2000 + i, i + 0.25f)
            queue.post(4, -i - 0.5f, i * 3.5f, Float.MAX_VALUE, -0f, i.toFloat(), 1e-30f)
        }

        val count = queue.beginDrain()

        assertEquals(40, count)
        for (i in 0 until 10) {
            val c = i * 4
            assertEquals(1, queue.method(c))
            assertEquals(2, queue.method(c + 1))
            assertEquals(1000 + i, queue.intArg(c + 1, 0))
            assertEquals(3, queue.method(c + 2))
            assertEquals(2000 + i, queue.intArg(c + 2, 0))
            assertEquals(i + 0.25f, queue.floatArg(c + 2, 1), 0f)
            assertEquals(4, queue.method(c + 3))
            assertEquals(-i - 0.5f, queue.floatArg(c + 3, 0), 0f)
            assertEquals(i * 3.5f, queue.floatArg(c + 3, 1), 0f)
            assertEquals(Float.MAX_VALUE, queue.floatArg(c + 3, 2), 0f)
            assertEquals((-0f).toRawBits(), queue.floatArg(c + 3, 3).toRawBits())
            assertEquals(i.toFloat(), queue.floatArg(c + 3, 4), 0f)
            assertEquals(1e-30f, queue.floatArg(c + 3, 5), 0f)
        }
        queue.endDrain()
        assertEquals(0, queue.beginDrain())
    }

    @Test
    fun callsPostedWhileABatchRunsWaitForTheNextDrain() {
        queue.post(5, 1f, 2f)

        assertEquals(1, queue.beginDrain())
        // The render thread runs the batch; the UI thread posts meanwhile.
        queue.post(6, 3f, 4f, 5f)
        assertEquals(5, queue.method(0))
        assertEquals(2f, queue.floatArg(0, 1), 0f)
        queue.endDrain()

        assertEquals(1, queue.beginDrain())
        assertEquals(6, queue.method(0))
        assertEquals(5f, queue.floatArg(0, 2), 0f)
        queue.endDrain()
    }

    @Test
    fun postingAndDrainingTouchEventsAllocateNothing() {
        // A drag: a down, then a scroll per frame, as GalleryView posts them.
        dragFrames(FRAMES) // warm up
        val bytes = minAllocatedBytes(RUNS) { dragFrames(FRAMES) }

        assertEquals("fewest bytes allocated by $FRAMES post/drain frames in $RUNS runs", 0L, bytes)
    }

    private fun dragFrames(frames: Int) {
        var sum = 0f
        for (i in 0 until frames) {
            queue.post(METHOD_DOWN)
            queue.post(METHOD_SCROLL, i.toFloat(), -1f, 2f * i, 3f, 4f + i, 5f)
            queue.post(METHOD_SCALE, 540f, 1200f, 1.01f)
            val count = queue.beginDrain()
            for (k in 0 until count) {
                if (queue.method(k) == METHOD_SCROLL) sum += queue.floatArg(k, 4)
            }
            queue.endDrain()
        }
        check(sum >= 0f)
    }

    private companion object {
        const val FRAMES = 300
        const val RUNS = 5
        const val METHOD_DOWN = 10
        const val METHOD_SCROLL = 5
        const val METHOD_SCALE = 8
    }
}
