/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GalleryOpenHelperCompletenessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dirWithPages(vararg names: String) = tmp.newFolder().also { dir ->
        names.forEach { java.io.File(dir, it).writeBytes(ByteArray(8)) }
    }

    @Test
    fun `countImageFiles counts only supported image extensions`() {
        val dir = dirWithPages("0001.jpg", "0002.png", ".nomedia", "0003.tmp")
        assertEquals(2, GalleryOpenHelper.countImageFiles(dir))
    }

    @Test
    fun `complete when file count reaches pagecount`() {
        val dir = dirWithPages("0001.jpg", "0002.jpg", "0003.jpg")
        assertTrue(GalleryOpenHelper.isLocalCopyComplete(dir, 3))
    }

    @Test
    fun `incomplete when files are missing`() {
        val dir = dirWithPages("0001.jpg", "0002.jpg")
        assertFalse(GalleryOpenHelper.isLocalCopyComplete(dir, 3))
    }

    @Test
    fun `unknown pagecount is treated as complete`() {
        val dir = dirWithPages("0001.jpg")
        assertTrue(GalleryOpenHelper.isLocalCopyComplete(dir, 0))
    }
}
