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
 * Parcelable round-trip tests for [LocalFavoriteInfo].
 *
 * Locks the post-W36-10 wire format. Field set:
 *   persistent: arcid, title, thumb, rating, simpleLanguage,
 *               serverProfileId, time
 *   @Ignore:    simpleTags
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LocalFavoriteInfoParcelTest {

    @Test
    fun parcelRoundTrip_preservesAllFields() {
        val original = LocalFavoriteInfo().apply {
            arcid = "fav-1"
            title = "Favorite Test"
            thumb = "https://example.com/f.jpg"
            rating = 5.0f
            simpleLanguage = "JA"
            serverProfileId = 11L
            time = 1710000000000L
            simpleTags = arrayOf("artist:kawaii", "lang:japanese")
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = LocalFavoriteInfo.CREATOR.createFromParcel(parcel)

            assertEquals(original.arcid, restored.arcid)
            assertEquals(original.title, restored.title)
            assertEquals(original.thumb, restored.thumb)
            assertEquals(original.rating, restored.rating, 0.001f)
            assertEquals(original.simpleLanguage, restored.simpleLanguage)
            assertEquals(original.serverProfileId, restored.serverProfileId)
            assertEquals(original.time, restored.time)
            assertArrayEquals(original.simpleTags, restored.simpleTags)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun parcelRoundTrip_withNullFields() {
        val original = LocalFavoriteInfo().apply {
            arcid = "f-null"
            title = null
            thumb = null
            simpleLanguage = null
            simpleTags = null
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = LocalFavoriteInfo.CREATOR.createFromParcel(parcel)

            assertEquals("f-null", restored.arcid)
            assertNull(restored.title)
            assertNull(restored.thumb)
            assertNull(restored.simpleLanguage)
            assertNull(restored.simpleTags)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun describeContents_returnsZero() {
        assertEquals(0, LocalFavoriteInfo().describeContents())
    }

    @Test
    fun creatorNewArray_returnsCorrectSize() {
        assertEquals(2, LocalFavoriteInfo.CREATOR.newArray(2).size)
    }
}
