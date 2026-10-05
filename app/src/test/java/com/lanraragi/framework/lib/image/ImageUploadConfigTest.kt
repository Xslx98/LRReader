package com.lanraragi.framework.lib.image

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-10-04 C14 / STAB-06: only RGBA_8888 reaches the native tile upload as-is. */
class ImageUploadConfigTest {

    @Test
    fun argb8888_isUploadedDirectly() {
        assertFalse(Image.needsArgb8888Copy(Bitmap.Config.ARGB_8888))
    }

    @Test
    fun otherConfigs_areCopiedFirst() {
        assertTrue(Image.needsArgb8888Copy(Bitmap.Config.RGBA_F16))
        assertTrue(Image.needsArgb8888Copy(Bitmap.Config.RGB_565))
        assertTrue(Image.needsArgb8888Copy(Bitmap.Config.HARDWARE))
        assertTrue(Image.needsArgb8888Copy(null))
    }
}
