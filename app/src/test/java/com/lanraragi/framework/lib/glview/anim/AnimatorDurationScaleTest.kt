package com.lanraragi.framework.lib.glview.anim

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimatorDurationScaleTest {

    @Test
    fun scale_zero_finishesImmediately() {
        assertEquals(0L, AnimatorDurationScale.scale(300L, 0f))
    }

    @Test
    fun scale_isProportional() {
        assertEquals(300L, AnimatorDurationScale.scale(300L, 1f))
        assertEquals(150L, AnimatorDurationScale.scale(300L, 0.5f))
        assertEquals(600L, AnimatorDurationScale.scale(300L, 2f))
    }

    @Test
    fun scale_negativeFactor_treatedAsZero() {
        assertEquals(0L, AnimatorDurationScale.scale(300L, -1f))
    }
}
