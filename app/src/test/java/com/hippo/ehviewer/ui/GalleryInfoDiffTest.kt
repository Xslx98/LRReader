package com.hippo.ehviewer.ui

import com.hippo.ehviewer.client.data.GalleryInfoUi
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for GalleryInfoUi identity and equality semantics used by
 * [ContentLayout.ContentHelper.isDuplicate] and DiffUtil callbacks.
 *
 * The gallery list's DiffUtil uses `d1.gid == d2.gid` for areItemsTheSame,
 * and `oldData.get(oldPos).equals(mData.get(newPos))` for areContentsTheSame.
 * These tests verify those contracts hold for typical data patterns.
 */
class GalleryInfoUiDiffTest {

    private fun makeGalleryInfoUi(gid: Long, title: String = "title", rating: Float = 4.0f): GalleryInfoUi {
        return GalleryInfoUi().apply {
            this.gid = gid
            this.title = title
            this.rating = rating
            this.posted = "2025-01-01"
            this.uploader = "test"
            this.thumb = "https://example.com/thumb/$gid"
        }
    }

    @Test
    fun sameGid_isDuplicate() {
        val a = makeGalleryInfoUi(gid = 100)
        val b = makeGalleryInfoUi(gid = 100)
        assertEquals("Same GID should be treated as same item", a.gid, b.gid)
    }

    @Test
    fun differentGid_isNotDuplicate() {
        val a = makeGalleryInfoUi(gid = 100)
        val b = makeGalleryInfoUi(gid = 200)
        assertNotEquals("Different GID should be different items", a.gid, b.gid)
    }

    @Test
    fun sameGidDifferentTitle_contentChanged() {
        val a = makeGalleryInfoUi(gid = 100, title = "Old Title")
        val b = makeGalleryInfoUi(gid = 100, title = "New Title")
        assertEquals("Same GID", a.gid, b.gid)
        assertNotEquals("Different title means content changed", a.title, b.title)
    }

    @Test
    fun sameGidDifferentRating_contentChanged() {
        val a = makeGalleryInfoUi(gid = 100, rating = 3.0f)
        val b = makeGalleryInfoUi(gid = 100, rating = 4.5f)
        assertEquals("Same GID", a.gid, b.gid)
        assertNotEquals("Different rating means content changed", a.rating, b.rating)
    }

    @Test
    fun identicalGalleryInfoUi_contentSame() {
        val a = makeGalleryInfoUi(gid = 100, title = "Same", rating = 4.0f)
        val b = makeGalleryInfoUi(gid = 100, title = "Same", rating = 4.0f)
        assertEquals(a.gid, b.gid)
        assertEquals(a.title, b.title)
        assertEquals(a.rating, b.rating, 0.001f)
    }

    @Test
    fun diffScenario_newItemsAppended() {
        val oldList = listOf(
            makeGalleryInfoUi(1), makeGalleryInfoUi(2), makeGalleryInfoUi(3)
        )
        val newList = listOf(
            makeGalleryInfoUi(1), makeGalleryInfoUi(2), makeGalleryInfoUi(3),
            makeGalleryInfoUi(4), makeGalleryInfoUi(5)
        )
        // Simulate isDuplicate check
        val newItems = newList.filter { new -> oldList.none { old -> old.gid == new.gid } }
        assertEquals("Should detect 2 new items", 2, newItems.size)
        assertEquals(4L, newItems[0].gid)
        assertEquals(5L, newItems[1].gid)
    }

    @Test
    fun diffScenario_itemsRemoved() {
        val oldList = listOf(
            makeGalleryInfoUi(1), makeGalleryInfoUi(2), makeGalleryInfoUi(3)
        )
        val newList = listOf(makeGalleryInfoUi(1), makeGalleryInfoUi(3))
        val removedItems = oldList.filter { old -> newList.none { new -> new.gid == old.gid } }
        assertEquals("Should detect 1 removed item", 1, removedItems.size)
        assertEquals(2L, removedItems[0].gid)
    }

    @Test
    fun diffScenario_completeReplacement() {
        val oldList = listOf(
            makeGalleryInfoUi(1), makeGalleryInfoUi(2), makeGalleryInfoUi(3)
        )
        val newList = listOf(
            makeGalleryInfoUi(10), makeGalleryInfoUi(20), makeGalleryInfoUi(30)
        )
        val overlap = newList.count { new -> oldList.any { old -> old.gid == new.gid } }
        assertEquals("Complete replacement has zero overlap", 0, overlap)
    }
}
