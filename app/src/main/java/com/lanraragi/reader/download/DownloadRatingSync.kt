package com.lanraragi.reader.download

import com.lanraragi.reader.domain.parseRatingFromTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fetches server ratings for one profile's downloaded archives, as cheaply
 * as the library allows. Used by the cold-start rating sync, which used to
 * send one metadata GET per download, sequentially, on every launch —
 * hours of doomed connect timeouts when the server was unreachable.
 *
 *  1. The profile's server is probed once; unreachable → skipped whole.
 *  2. When the library is small relative to the downloads, the search
 *     listing (which carries tags) is paged through: a few dozen requests
 *     cover thousands of downloads.
 *  3. Otherwise per-archive metadata requests, [PARALLELISM] at a time.
 *
 * (Once-per-[SYNC_INTERVAL_MS] throttling is the caller's job.)
 */
class DownloadRatingSync(
    private val probe: suspend (baseUrl: String) -> Boolean,
    private val searchPage: suspend (baseUrl: String, start: Int) -> Page,
    private val fetchTags: suspend (baseUrl: String, arcid: String) -> String,
) {

    /** One search page: (arcid, tags) entries and the library size. */
    data class Page(val entries: List<Pair<String, String>>, val total: Int)

    /**
     * Server rating per arcid in [arcids] (-1 = no rating tag), or null
     * when the server is unreachable. Archives that fail individually are
     * left out.
     */
    suspend fun ratings(baseUrl: String, arcids: Set<String>): Map<String, Float>? {
        if (arcids.isEmpty()) return emptyMap()
        if (!probe(baseUrl)) return null
        val first = searchPage(baseUrl, 0)
        return if (first.total <= arcids.size * BULK_FACTOR) {
            bulk(baseUrl, arcids, first)
        } else {
            perArchive(baseUrl, arcids)
        }
    }

    private suspend fun bulk(baseUrl: String, arcids: Set<String>, first: Page): Map<String, Float> {
        val out = HashMap<String, Float>()
        var page = first
        var seen = 0
        while (true) {
            for ((arcid, tags) in page.entries) {
                if (arcid in arcids) out[arcid] = parseRatingFromTags(tags)
            }
            seen += page.entries.size
            if (page.entries.isEmpty() || seen >= page.total || out.size == arcids.size) break
            page = searchPage(baseUrl, seen)
        }
        return out
    }

    private suspend fun perArchive(baseUrl: String, arcids: Set<String>): Map<String, Float> = coroutineScope {
        val gate = Semaphore(PARALLELISM)
        arcids.map { arcid ->
            async {
                gate.withPermit {
                    try {
                        arcid to parseRatingFromTags(fetchTags(baseUrl, arcid))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    companion object {
        /** Page through the whole library when it is at most this many times the downloads. */
        const val BULK_FACTOR = 4
        const val PARALLELISM = 4
        const val SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
