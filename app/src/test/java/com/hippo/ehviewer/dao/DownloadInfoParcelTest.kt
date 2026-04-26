package com.hippo.ehviewer.dao

import android.os.Parcel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parcelable round-trip tests for [DownloadInfo].
 *
 * Locks the post-W36-10 wire format. Field set:
 *   persistent: arcid, title, thumb, rating, simpleLanguage,
 *               serverProfileId, state, legacy, time, label, archiveUri
 *   @Ignore:    simpleTags, tgList, fileSize
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadInfoParcelTest {

    @Test
    fun parcelRoundTrip_preservesAllFields() {
        val original = DownloadInfo().apply {
            arcid = "xyz789"
            title = "Download Test"
            thumb = "https://example.com/dl_thumb.jpg"
            rating = 3.5f
            simpleLanguage = "ZH"
            serverProfileId = 42L
            state = DownloadInfo.STATE_DOWNLOAD
            legacy = 1
            time = 1672531200000L
            label = "my-label"
            archiveUri = "content://downloads/123"
            simpleTags = arrayOf("lang:chinese", "artist:someone")
            tgList = arrayListOf("artist:someone", "lang:chinese")
            fileSize = 12345L
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = DownloadInfo.CREATOR.createFromParcel(parcel)

            assertEquals(original.arcid, restored.arcid)
            assertEquals(original.title, restored.title)
            assertEquals(original.thumb, restored.thumb)
            assertEquals(original.rating, restored.rating, 0.001f)
            assertEquals(original.simpleLanguage, restored.simpleLanguage)
            assertEquals(original.serverProfileId, restored.serverProfileId)
            assertEquals(original.state, restored.state)
            assertEquals(original.legacy, restored.legacy)
            assertEquals(original.time, restored.time)
            assertEquals(original.label, restored.label)
            assertEquals(original.archiveUri, restored.archiveUri)
            assertArrayEquals(original.simpleTags, restored.simpleTags)
            assertNotNull(restored.tgList)
            assertEquals(original.tgList, restored.tgList)
            assertEquals(original.fileSize, restored.fileSize)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun parcelRoundTrip_withNullFields() {
        val original = DownloadInfo().apply {
            arcid = "n1"
            title = null
            thumb = null
            simpleLanguage = null
            label = null
            archiveUri = null
            simpleTags = null
            tgList = null
        }

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = DownloadInfo.CREATOR.createFromParcel(parcel)

            assertEquals("n1", restored.arcid)
            assertNull(restored.title)
            assertNull(restored.thumb)
            assertNull(restored.simpleLanguage)
            assertNull(restored.label)
            assertNull(restored.archiveUri)
            assertNull(restored.simpleTags)
            assertNull(restored.tgList)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun parcelRoundTrip_preservesDefaults() {
        val original = DownloadInfo()

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = DownloadInfo.CREATOR.createFromParcel(parcel)

            assertEquals("", restored.arcid)
            assertEquals(0f, restored.rating, 0.001f)
            assertEquals(0, restored.state)
            assertEquals(0, restored.legacy)
            assertEquals(0L, restored.time)
            assertEquals(0L, restored.serverProfileId)
            assertEquals(-1L, restored.fileSize)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun describeContents_returnsZero() {
        assertEquals(0, DownloadInfo().describeContents())
    }

    @Test
    fun creatorNewArray_returnsCorrectSize() {
        assertEquals(3, DownloadInfo.CREATOR.newArray(3).size)
    }
}
