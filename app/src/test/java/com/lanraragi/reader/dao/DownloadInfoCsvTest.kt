package com.lanraragi.reader.dao

import com.lanraragi.reader.util.DownloadCsvTooLargeException
import com.lanraragi.reader.util.archiveFromCsvLine
import com.lanraragi.reader.util.readDownloadCsv
import com.lanraragi.reader.util.toCSV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import com.lanraragi.reader.download.DownloadManager
import com.lanraragi.reader.download.DownloadState
import java.io.ByteArrayInputStream

/**
 * Validates that [DownloadInfo.toCSV] produces a 20-column line that
 * [archiveFromCsvLine] parses back into the right Archive shape.
 *
 * Guards the user-facing download list import/export feature in
 * DownloadFragment.exportDownloadItems / executeImportDownload.
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
            state = DownloadState.FINISH
            time = 1700000000000L
            label = "sample"
            simpleTags = arrayOf("artist:foo")
        }

        val csv = di.toCSV()
        assertEquals(20, csv.trimEnd('\n').split(",").size)

        val parsed = archiveFromCsvLine(csv.trimEnd('\n'))
        assertNotNull(parsed)
        assertEquals("csv-arc-1", parsed!!.arcid)
        assertEquals("CSV Test", parsed.title)
        assertEquals("https://example.com/t.jpg", parsed.thumbnailUrl)
        assertEquals(4.5f, parsed.rating, 0.001f)
        // simpleLanguage is read past but not exposed on Archive — verify
        // tag parsing instead, since simpleTags survives.
        assertEquals(listOf("foo"), parsed.tags["artist"])
    }

    @Test
    fun pre_flatten_csv_format_roundTripsToArchive() {
        // Simulate a CSV line shaped like the historical
        // GalleryInfoEntity.toCSV output that older app versions wrote.
        // Wire format is frozen and must keep parsing into a valid Archive.
        val oldCsv = "0,old-csv-1,Old CSV,null,https://old/t.jpg,0,null,null,3.0,false,JA,[lang:japanese],0,0,0,0,0,-2,null,0"

        val parsed = archiveFromCsvLine(oldCsv)
        assertNotNull(parsed)
        assertEquals("old-csv-1", parsed!!.arcid)
        assertEquals("Old CSV", parsed.title)
        assertEquals("https://old/t.jpg", parsed.thumbnailUrl)
        assertEquals(3.0f, parsed.rating, 0.001f)
        assertEquals(listOf("japanese"), parsed.tags["lang"])
    }

    private val sampleFile =
        DownloadManager.DOWNLOAD_INFO_HEADER + "\n" +
            "0,a1,A,null,t,0,null,null,1.0,false,EN,[],0,0,0,0,0,-2,null,0\r\n" +
            "not a csv line\n" +
            "0,a2,B,null,t,0,null,null,2.0,false,EN,[],0,0,0,0,0,-2,null,0\n"

    @Test
    fun readDownloadCsv_skipsHeaderAndBadLines() {
        val archives = readDownloadCsv(ByteArrayInputStream(sampleFile.toByteArray()))
        assertEquals(listOf("a1", "a2"), archives.map { it.arcid })
    }

    @Test
    fun readDownloadCsv_rejectsFileOverTheCap() {
        val bytes = sampleFile.toByteArray()
        assertEquals(2, readDownloadCsv(ByteArrayInputStream(bytes), maxBytes = bytes.size.toLong()).size)
        assertThrows(DownloadCsvTooLargeException::class.java) {
            readDownloadCsv(ByteArrayInputStream(bytes), maxBytes = bytes.size - 1L)
        }
    }
}
