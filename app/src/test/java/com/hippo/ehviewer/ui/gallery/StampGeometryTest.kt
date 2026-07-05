package com.hippo.ehviewer.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StampGeometryTest {

    @Test
    fun parsePosition_floats() {
        val p = StampGeometry.parsePosition("12.5,34")!!
        assertEquals(12.5f, p.first, 0.0001f)
        assertEquals(34f, p.second, 0.0001f)
    }

    @Test
    fun parsePosition_clampsOutOfRange() {
        val p = StampGeometry.parsePosition("-5,150")!!
        assertEquals(0f, p.first, 0.0001f)
        assertEquals(100f, p.second, 0.0001f)
    }

    @Test
    fun parsePosition_malformed_returnsNull() {
        assertNull(StampGeometry.parsePosition(""))
        assertNull(StampGeometry.parsePosition("12"))
        assertNull(StampGeometry.parsePosition("a,b"))
        assertNull(StampGeometry.parsePosition("1,2,3"))
    }

    @Test
    fun formatPosition_usLocaleTwoDecimals() {
        assertEquals("12.50,34.00", StampGeometry.formatPosition(12.5f, 34f))
        assertEquals("0.00,100.00", StampGeometry.formatPosition(-4f, 130f))
    }

    @Test
    fun roundTrip_formatThenParse() {
        val s = StampGeometry.formatPosition(33.33f, 66.67f)
        val p = StampGeometry.parsePosition(s)!!
        assertEquals(33.33f, p.first, 0.01f)
        assertEquals(66.67f, p.second, 0.01f)
    }

    @Test
    fun normToScreen_maps() {
        // image rect left=100 width=400: norm 25% -> 100 + 0.25*400 = 200
        assertEquals(200f, StampGeometry.normToScreen(25f, 100f, 400f), 0.0001f)
    }

    @Test
    fun screenToNorm_mapsAndClamps() {
        assertEquals(25f, StampGeometry.screenToNorm(200f, 100f, 400f), 0.0001f)
        assertEquals(0f, StampGeometry.screenToNorm(50f, 100f, 400f), 0.0001f)
        assertEquals(100f, StampGeometry.screenToNorm(9999f, 100f, 400f), 0.0001f)
    }

    @Test
    fun screenToNorm_nanRectSize_fallsBack() {
        assertEquals(50f, StampGeometry.screenToNorm(10f, 0f, Float.NaN), 0.0001f)
    }

    @Test
    fun formatPosition_nan_fallsBackToCenter() {
        assertEquals("50.00,34.00", StampGeometry.formatPosition(Float.NaN, 34f))
    }
}
