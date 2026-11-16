package com.lanraragi.framework.lib.glgallery

import com.lanraragi.framework.lib.glgallery.GestureMode.Idle
import com.lanraragi.framework.lib.glgallery.GestureMode.Scaling
import com.lanraragi.framework.lib.glgallery.GestureMode.Scrolling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's touch gesture as one value: which gesture owns the fingers, and when it ends. */
class GestureModeTest {

    @Test
    fun `a scroll starts from rest, never inside a pinch`() {
        assertEquals(Scrolling, Idle.scrolled())
        assertEquals(Scrolling, Scrolling.scrolled())
        assertNull("the finger left after a pinch does not scroll", Scaling.scrolled())
    }

    @Test
    fun `a pinch starts unless a scroll did, and only when the page can scale`() {
        assertEquals(Scaling, Idle.scaled(canScale = true))
        assertEquals(Scaling, Scaling.scaled(canScale = true))
        assertNull(Scrolling.scaled(canScale = true))
        assertNull(Idle.scaled(canScale = false))

        assertEquals(Scaling, Idle.pointerDown(canScale = true))
        assertEquals(Scrolling, Scrolling.pointerDown(canScale = true))
        assertEquals(Idle, Idle.pointerDown(canScale = false))
    }

    @Test
    fun `only a pinch blocks double tap and long press`() {
        assertTrue(Scaling.blocksTaps)
        for (mode in listOf(Idle, Scrolling)) assertFalse(mode.name, mode.blocksTaps)
    }

    @Test
    fun `lifting the last finger ends a scroll or a pinch`() {
        assertEquals(Idle, Scrolling.up())
        assertEquals(Idle, Scaling.up())
    }
}
