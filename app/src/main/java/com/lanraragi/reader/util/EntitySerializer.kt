package com.lanraragi.reader.util

import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.DownloadManager
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * CSV serialization for the download list import/export feature.
 *
 * The on-disk format is a 20-column line per archive, frozen for
 * backward compatibility with users' existing CSV exports. Most
 * columns are EH-era ceremony that LRR never populates; only the
 * columns the importer actually consumes carry real data:
 *
 *   index  column           role
 *   ─────  ───────────────  ─────────────────────────────────────
 *      1  arcid             primary key (LRR SHA-1)
 *      2  title             display title
 *      4  thumb             thumbnail URL
 *      8  rating            float, 0..5
 *     10  simpleLanguage    EN / JA / ZH / …
 *     11  simpleTags        bracketed Array.contentToString() form
 *
 * Other columns are written as fixed defaults on export and read
 * but discarded on import (legacy gid, titleJpn, category, posted,
 * uploader, rated, thumbWidth/Height, span*, favoriteSlot/Name,
 * pages). Slimming the format is a future task once no v1.x APK
 * remains in the wild.
 */

/**
 * Emit a single archive row as a 20-column CSV line. Wire format
 * matches the parser in [archiveFromCsvLine] and the historical
 * GalleryInfoEntity.toCSV that this replaces.
 */
fun DownloadInfo.toCSV(): String {
    return "0" + "," +                   // gid (dropped W36-10)
        arcid + "," +
        title + "," +
        "null" + "," +                   // titleJpn
        thumb + "," +
        "0" + "," +                      // category
        "null" + "," +                   // posted
        "null" + "," +                   // uploader
        rating + "," +
        "false" + "," +                  // rated
        simpleLanguage + "," +
        simpleTags.contentToString() + "," +
        "0,0,0,0,0," +                   // thumbWidth, thumbHeight, spanSize, spanIndex, spanGroupIndex
        "-2," +                          // favoriteSlot
        "null" + "," +                   // favoriteName
        "0\n"                            // pages
}

/**
 * Parse a single CSV line (produced by [DownloadInfo.toCSV] now or
 * by the historical GalleryInfoEntity.toCSV) into an [Archive]
 * domain object. Returns `null` when the line has fewer than 20
 * columns or contains unparseable numbers.
 *
 * Only fields the importer actually consumes (arcid / title / thumb /
 * rating / simpleLanguage / simpleTags) are extracted; the dead
 * legacy columns are read past but discarded.
 */
fun archiveFromCsvLine(csv: String): Archive? {
    val values = csv.split(",").dropLastWhile { it.isEmpty() }.toTypedArray()
    if (values.size < 20) {
        return null
    }
    return try {
        val arcid = values[1]
        val title = values[2]
        val thumb = values[4]
        val rating = values[8].toFloat()
        // values[10] is simpleLanguage — not exposed on Archive (language is
        // derived from tags at display time), so just read past it.
        val tagsCell = values[11]
        // simpleTags came from Array.contentToString(): "[a, b, c]". Strip
        // the surrounding brackets and split on ", " so each element ends
        // up as a `namespace:value` (or bare) token.
        val flatTags = if (tagsCell.length >= 2 && tagsCell.startsWith("[") && tagsCell.endsWith("]")) {
            tagsCell.substring(1, tagsCell.length - 1)
                .split(", ")
                .filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val tagsMap = LinkedHashMap<String, MutableList<String>>()
        for (tag in flatTags) {
            val colon = tag.indexOf(':')
            if (colon > 0) {
                tagsMap.getOrPut(tag.substring(0, colon).trim()) { mutableListOf() }
                    .add(tag.substring(colon + 1).trim())
            } else {
                tagsMap.getOrPut("misc") { mutableListOf() }.add(tag)
            }
        }
        Archive(
            arcid = arcid,
            title = title,
            tags = tagsMap,
            pagecount = 0,
            progress = 0,
            extension = "",
            filename = "",
            thumbnailUrl = thumb,
            rating = rating,
            isnew = false,
            lastreadtime = 0L,
            summary = null,
            serverProfileId = 0L,
        )
    } catch (e: NumberFormatException) {
        null
    }
}

/**
 * Largest download-list CSV the importer reads. A row is a few hundred
 * bytes, so this is far above any real list but keeps a wrongly picked
 * video or archive from being read into memory.
 */
const val MAX_DOWNLOAD_CSV_BYTES: Long = 8L * 1024 * 1024

/** The picked download-list file exceeds [MAX_DOWNLOAD_CSV_BYTES]. */
class DownloadCsvTooLargeException(maxBytes: Long) : IOException("CSV larger than $maxBytes bytes")

/**
 * Stream a download-list CSV line by line into archives, skipping the
 * header and unparseable lines. Reads at most [maxBytes] bytes and throws
 * [DownloadCsvTooLargeException] beyond that. Does not close [input].
 */
fun readDownloadCsv(input: InputStream, maxBytes: Long = MAX_DOWNLOAD_CSV_BYTES): List<Archive> =
    CappedInputStream(input, maxBytes).bufferedReader(Charsets.UTF_8).lineSequence()
        .filterNot { it.startsWith(DownloadManager.DOWNLOAD_INFO_HEADER) }
        .mapNotNull(::archiveFromCsvLine)
        .toList()

private class CappedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

    private fun add(n: Long) {
        count += n
        if (count > maxBytes) throw DownloadCsvTooLargeException(maxBytes)
    }
}
