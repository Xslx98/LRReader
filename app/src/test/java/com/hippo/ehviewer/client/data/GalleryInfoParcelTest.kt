package com.hippo.ehviewer.client.data

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parcelable round-trip tests for [GalleryInfo].
 *
 * Uses Robolectric to provide the Android Parcel implementation.
 * Verifies that all fields survive a write-then-read cycle.
 *
 * DownloadInfo round-trip lives in `dao/DownloadInfoParcelTest.kt` since
 * W36-7 (Entity flatten — no longer extends GalleryInfoEntity).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GalleryInfoParcelTest {

    @Test
    fun galleryInfo_parcelRoundTrip_preservesAllFields() {
        val original = GalleryInfo().apply {
            gid = 12345L
            arcid = "abc123"
            title = "Test Title"
            titleJpn = "\u30c6\u30b9\u30c8"
            thumb = "https://example.com/thumb.jpg"
            category = 2
            posted = "2026-01-01"
            uploader = "user1"
            rating = 4.5f
            rated = true
            simpleLanguage = "EN"
            simpleTags = arrayOf("tag1", "tag2")
            thumbWidth = 200
            thumbHeight = 300
            spanSize = 1
            spanIndex = 0
            spanGroupIndex = 5
            favoriteSlot = 3
            favoriteName = "Favorites"
            tgList = arrayListOf("artist:author1", "parody:series1")
            serverProfileId = 99L
            progress = 15
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = GalleryInfo.CREATOR.createFromParcel(parcel)

            assertEquals(original.gid, restored.gid)
            assertEquals(original.arcid, restored.arcid)
            assertEquals(original.title, restored.title)
            assertEquals(original.titleJpn, restored.titleJpn)
            assertEquals(original.thumb, restored.thumb)
            assertEquals(original.category, restored.category)
            assertEquals(original.posted, restored.posted)
            assertEquals(original.uploader, restored.uploader)
            assertEquals(original.rating, restored.rating, 0.001f)
            assertEquals(original.rated, restored.rated)
            assertEquals(original.simpleLanguage, restored.simpleLanguage)
            assertArrayEquals(original.simpleTags, restored.simpleTags)
            assertEquals(original.thumbWidth, restored.thumbWidth)
            assertEquals(original.thumbHeight, restored.thumbHeight)
            assertEquals(original.spanSize, restored.spanSize)
            assertEquals(original.spanIndex, restored.spanIndex)
            assertEquals(original.spanGroupIndex, restored.spanGroupIndex)
            assertEquals(original.favoriteSlot, restored.favoriteSlot)
            assertEquals(original.favoriteName, restored.favoriteName)
            assertNotNull(restored.tgList)
            assertEquals(original.tgList, restored.tgList)
            assertEquals(original.serverProfileId, restored.serverProfileId)
            assertEquals(original.progress, restored.progress)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun galleryInfo_parcelRoundTrip_withNullFields() {
        val original = GalleryInfo().apply {
            gid = 1L
            arcid = ""
            title = null
            titleJpn = null
            thumb = null
            posted = null
            uploader = null
            simpleLanguage = null
            simpleTags = null
            favoriteName = null
            tgList = null
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = GalleryInfo.CREATOR.createFromParcel(parcel)

            assertEquals(original.gid, restored.gid)
            assertEquals("", restored.arcid)
            assertNull(restored.title)
            assertNull(restored.titleJpn)
            assertNull(restored.thumb)
            assertNull(restored.posted)
            assertNull(restored.uploader)
            assertNull(restored.simpleLanguage)
            assertNull(restored.simpleTags)
            assertNull(restored.favoriteName)
            assertNull(restored.tgList)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun galleryInfo_parcelRoundTrip_preservesDefaultValues() {
        val original = GalleryInfo()

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = GalleryInfo.CREATOR.createFromParcel(parcel)

            assertEquals(0L, restored.gid)
            assertEquals(0, restored.category)
            assertEquals(0f, restored.rating, 0.001f)
            assertEquals(false, restored.rated)
            assertEquals(0, restored.thumbWidth)
            assertEquals(0, restored.thumbHeight)
            assertEquals(0, restored.spanSize)
            assertEquals(0, restored.spanIndex)
            assertEquals(0, restored.spanGroupIndex)
            // Default favoriteSlot is -2
            assertEquals(-2, restored.favoriteSlot)
            assertEquals(0L, restored.serverProfileId)
            assertEquals(0, restored.progress)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun galleryInfo_parcelRoundTrip_emptySimpleTags() {
        val original = GalleryInfo().apply {
            simpleTags = arrayOf()
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = GalleryInfo.CREATOR.createFromParcel(parcel)

            assertNotNull(restored.simpleTags)
            assertEquals(0, restored.simpleTags!!.size)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun galleryInfo_parcelRoundTrip_emptyTgList() {
        val original = GalleryInfo().apply {
            tgList = arrayListOf()
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = GalleryInfo.CREATOR.createFromParcel(parcel)

            assertNotNull(restored.tgList)
            assertTrue(restored.tgList!!.isEmpty())
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun galleryInfo_describeContents_returnsZero() {
        assertEquals(0, GalleryInfo().describeContents())
    }

    @Test
    fun galleryInfo_creatorNewArray_returnsCorrectSize() {
        val array = GalleryInfo.CREATOR.newArray(5)
        assertEquals(5, array.size)
    }

}
