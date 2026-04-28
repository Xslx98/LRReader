package com.lanraragi.reader.domain

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip tests for [ArchiveDetail.writeToParcel] / Parcelable
 * constructor. The detail page persists this whole structure across
 * `Scene.onSaveInstanceState` so a regression here would silently lose
 * detail-page state on rotation or process death.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ArchiveDetailParcelTest {

    private fun sampleArchive(): Archive = Archive(
        arcid = "abc123",
        title = "Sample",
        tags = mapOf("artist" to listOf("alice"), "language" to listOf("english")),
        pagecount = 12,
        progress = 3,
        extension = "zip",
        filename = "sample.zip",
        thumbnailUrl = "https://example.test/t",
        rating = 3.5f,
        isnew = false,
        lastreadtime = 100L,
        summary = "blurb",
        serverProfileId = 7L,
    )

    @Test
    fun `round-trip preserves every field with populated tag groups`() {
        val original = ArchiveDetail(
            archive = sampleArchive(),
            tagGroups = listOf(
                TagGroup("artist", listOf("alice", "bob")),
                TagGroup("language", listOf("english")),
            ),
            language = "English",
            size = "120 MB",
        )

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = ArchiveDetail.CREATOR.createFromParcel(parcel)
            assertEquals(original, restored)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `round-trip preserves empty tag groups`() {
        val original = ArchiveDetail(
            archive = sampleArchive(),
            tagGroups = emptyList(),
            language = "N/A",
            size = "N/A",
        )

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = ArchiveDetail.CREATOR.createFromParcel(parcel)
            assertTrue(restored.tagGroups.isEmpty())
            assertEquals(original.archive.arcid, restored.archive.arcid)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `round-trip preserves null language and size`() {
        val original = ArchiveDetail(
            archive = sampleArchive(),
            tagGroups = listOf(TagGroup("misc", listOf("standalone"))),
            language = null,
            size = null,
        )

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = ArchiveDetail.CREATOR.createFromParcel(parcel)
            assertNull(restored.language)
            assertNull(restored.size)
            assertEquals(listOf("standalone"), restored.tagGroups[0].tags)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `round-trip preserves nested archive tags map`() {
        val original = ArchiveDetail(
            archive = sampleArchive(),
            tagGroups = emptyList(),
            language = null,
            size = null,
        )

        val parcel = Parcel.obtain()
        try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = ArchiveDetail.CREATOR.createFromParcel(parcel)
            assertEquals(listOf("alice"), restored.archive.tags["artist"])
            assertEquals(listOf("english"), restored.archive.tags["language"])
        } finally {
            parcel.recycle()
        }
    }
}
