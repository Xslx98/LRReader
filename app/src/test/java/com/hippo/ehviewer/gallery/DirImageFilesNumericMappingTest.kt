/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirImageFilesNumericMappingTest {

    @Test
    fun `maps worker-style 4-digit names`() {
        val map = DirImageFiles.numericPageIndices(listOf("0001.jpg", "0002.png", "0010.webp"))
        assertEquals(mapOf(0 to 0, 1 to 1, 9 to 2), map)
    }

    @Test
    fun `a download gap maps real page numbers - no shift`() {
        val map = DirImageFiles.numericPageIndices(listOf("0001.jpg", "0003.jpg"))
        assertEquals(mapOf(0 to 0, 2 to 1), map)
    }

    @Test
    fun `legacy 8-digit names map too`() {
        val map = DirImageFiles.numericPageIndices(listOf("00000001.jpg", "00000002.jpg"))
        assertEquals(mapOf(0 to 0, 1 to 1), map)
    }

    @Test
    fun `non-numeric names fall back to null`() {
        assertNull(DirImageFiles.numericPageIndices(listOf("cover.jpg", "0001.jpg")))
    }

    @Test
    fun `duplicate page numbers fall back to null`() {
        assertNull(DirImageFiles.numericPageIndices(listOf("0001.jpg", "0001.webp")))
    }

    @Test
    fun `page zero falls back to null`() {
        assertNull(DirImageFiles.numericPageIndices(listOf("0000.jpg")))
    }

    @Test
    fun `empty list falls back to null`() {
        assertNull(DirImageFiles.numericPageIndices(emptyList()))
    }

    // ── pageSpaceSize ────────────────────────────────────────────────

    @Test
    fun `pageSpaceSize positional dir uses the file count`() {
        // null map -> ignore pagecount, report exactly the files present.
        assertEquals(40, DirImageFiles.pageSpaceSize(null, 40, 50))
    }

    @Test
    fun `pageSpaceSize complete archive`() {
        val map = (0..49).associateWith { it }
        assertEquals(50, DirImageFiles.pageSpaceSize(map, 50, 50))
    }

    @Test
    fun `pageSpaceSize interior gap keeps full length`() {
        // pages 0..49 present except 29 -> 49 files, maxPage 50, pagecount 50.
        val map = (0..49).filter { it != 29 }.mapIndexed { pos, page -> page to pos }.toMap()
        assertEquals(50, DirImageFiles.pageSpaceSize(map, 49, 50))
    }

    @Test
    fun `pageSpaceSize trailing gap uses expectedPageCount`() {
        // only pages 0..39 downloaded of a 50-page archive.
        val map = (0..39).associateWith { it }
        assertEquals(50, DirImageFiles.pageSpaceSize(map, 40, 50))
    }

    @Test
    fun `pageSpaceSize stale-high pagecount produces phantom tail`() {
        // all 50 real pages downloaded but server pagecount drifted to 60.
        val map = (0..49).associateWith { it }
        assertEquals(60, DirImageFiles.pageSpaceSize(map, 50, 60))
    }

    @Test
    fun `pageSpaceSize unknown pagecount falls back to max page`() {
        // downloads-list path: pagecount 0 -> size by the highest page number.
        val map = (0..49).associateWith { it }
        assertEquals(50, DirImageFiles.pageSpaceSize(map, 50, 0))
    }
}
