package com.hippo.ehviewer.dao

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parcelable round-trip tests for [HistoryInfo].
 *
 * Pre-W36-8 (extends GalleryInfoEntity): inherited fields go through
 * super.writeToParcel(); this test asserts only the field set HistoryInfo
 * keeps post-flatten so it stays green across the refactor and locks
 * the new wire format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class HistoryInfoParcelTest {

    @Test
    fun parcelRoundTrip_preservesAllFields() {
        val original = HistoryInfo().apply {
            arcid = "hist-1"
            gid = 100L
            title = "History Test"
            titleJpn = "履歴"
            thumb = "https://example.com/h.jpg"
            category = 2
            posted = "2026-04-01"
            uploader = "u1"
            rating = 4.0f
            simpleLanguage = "EN"
            serverProfileId = 7L
            mode = 1
            time = 1700000000000L
            simpleTags = arrayOf("tag1")
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = HistoryInfo.CREATOR.createFromParcel(parcel)

            assertEquals(original.arcid, restored.arcid)
            assertEquals(original.gid, restored.gid)
            assertEquals(original.title, restored.title)
            assertEquals(original.titleJpn, restored.titleJpn)
            assertEquals(original.thumb, restored.thumb)
            assertEquals(original.category, restored.category)
            assertEquals(original.posted, restored.posted)
            assertEquals(original.uploader, restored.uploader)
            assertEquals(original.rating, restored.rating, 0.001f)
            assertEquals(original.simpleLanguage, restored.simpleLanguage)
            assertEquals(original.serverProfileId, restored.serverProfileId)
            assertEquals(original.mode, restored.mode)
            assertEquals(original.time, restored.time)
            assertArrayEquals(original.simpleTags, restored.simpleTags)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun parcelRoundTrip_withNullFields() {
        val original = HistoryInfo().apply {
            arcid = "h-null"
            title = null
            titleJpn = null
            thumb = null
            posted = null
            uploader = null
            simpleLanguage = null
            simpleTags = null
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = HistoryInfo.CREATOR.createFromParcel(parcel)

            assertEquals("h-null", restored.arcid)
            assertNull(restored.title)
            assertNull(restored.titleJpn)
            assertNull(restored.thumb)
            assertNull(restored.posted)
            assertNull(restored.uploader)
            assertNull(restored.simpleLanguage)
            assertNull(restored.simpleTags)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun describeContents_returnsZero() {
        assertEquals(0, HistoryInfo().describeContents())
    }

    @Test
    fun creatorNewArray_returnsCorrectSize() {
        assertEquals(4, HistoryInfo.CREATOR.newArray(4).size)
    }
}
