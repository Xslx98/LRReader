package com.lanraragi.reader.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GallerySliderBubbleTest {

    @Test
    fun bubbleOffset_centresOnTheThumb() {
        assertEquals(450f, GallerySliderController.bubbleOffset(500f, 100, 1080), 0.001f)
    }

    @Test
    fun bubbleOffset_staysInsideTheRowAtBothEnds() {
        assertEquals(0f, GallerySliderController.bubbleOffset(20f, 100, 1080), 0.001f)
        assertEquals(980f, GallerySliderController.bubbleOffset(1070f, 100, 1080), 0.001f)
    }

    @Test
    fun bubbleOffset_bubbleWiderThanRow_pinsToStart() {
        assertEquals(0f, GallerySliderController.bubbleOffset(40f, 200, 100), 0.001f)
    }
}
