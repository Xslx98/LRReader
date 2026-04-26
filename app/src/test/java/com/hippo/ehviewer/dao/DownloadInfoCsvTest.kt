package com.hippo.ehviewer.dao

import com.hippo.ehviewer.client.data.GalleryInfoEntity
import com.hippo.ehviewer.util.galleryInfoFromCSV
import com.hippo.ehviewer.util.toCSV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Validates that DownloadInfo.toCSV() produces the same 20-column wire
 * format that galleryInfoFromCSV() can parse back into a GalleryInfoEntity.
 *
 * Guards the user-facing download list import/export feature in
 * DownloadFragment.exportDownloadItems / executeImportDownload.
 *
 * The wire format must remain stable across W36-7 — old CSV files
 * exported by v1.12.0 (pre-flatten) MUST import on post-flatten APK.
 *
 * Note: legacy format does NOT escape commas inside `simpleTags`
 * (`Array.contentToString()` emits `[a, b]` with `, ` separator), so
 * the column count assertion only holds for tag arrays of length <= 1.
 * Slimming this format is a Phase 4 task.
 */
class DownloadInfoCsvTest {

    @Test
    fun toCSV_producesParseableLine() {
        val di = DownloadInfo().apply {
            arcid = "csv-arc-1"
            title = "CSV Test"
            thumb = "https://example.com/t.jpg"
            rating = 4.5f
            simpleLanguage = "EN"
            serverProfileId = 1L
            state = DownloadInfo.STATE_FINISH
            time = 1700000000000L
            label = "sample"
            simpleTags = arrayOf("artist:foo")
        }

        val csv = di.toCSV()
        assertEquals(20, csv.trimEnd('\n').split(",").size)

        val parsed: GalleryInfoEntity? = galleryInfoFromCSV(csv.trimEnd('\n'))
        assertNotNull(parsed)
        assertEquals("csv-arc-1", parsed!!.arcid)
        assertEquals("CSV Test", parsed.title)
        assertEquals("https://example.com/t.jpg", parsed.thumb)
        assertEquals(4.5f, parsed.rating, 0.001f)
        assertEquals("EN", parsed.simpleLanguage)
    }

    @Test
    fun oldGalleryInfoEntityCsv_roundTripsThroughGalleryInfoEntity() {
        // Simulates a CSV line produced by an older app version
        // (pre-flatten DownloadInfo went through GalleryInfoEntity.toCSV()).
        val gi = GalleryInfoEntity().apply {
            arcid = "old-csv-1"
            title = "Old CSV"
            thumb = "https://old/t.jpg"
            rating = 3.0f
            simpleLanguage = "JA"
            serverProfileId = 0L
        }
        val oldCsv = gi.toCSV()

        val parsed = galleryInfoFromCSV(oldCsv.trimEnd('\n'))
        assertNotNull(parsed)
        assertEquals("old-csv-1", parsed!!.arcid)
        assertEquals("Old CSV", parsed.title)
    }
}
