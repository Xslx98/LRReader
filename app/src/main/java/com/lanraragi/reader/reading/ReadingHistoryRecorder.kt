package com.lanraragi.reader.reading

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.server.resolveSourceBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reader entry counts as reading: puts the session's archive into history so
 * per-archive state (intra-page scroll fraction, etc.) lands on a row the
 * history queries can see. This is the only place a single archive enters
 * history: opening its detail page or starting its download is not reading
 * (audit 2026-10-04).
 *
 * Tank sessions get ONE history row for the whole tank (tank-only
 * bookkeeping): a pseudo-archive keyed by the TANK_ id whose thumbnail rides
 * the tank cover route. HistoryScene branches its click on isTankoubonId and
 * resumes the composite session.
 *
 * Fire-and-forget on [scope], a process scope, so closing the reader right
 * away still records the entry; failures are logged and dropped.
 *
 * @param scope null = the app's ioScope, resolved at first use
 * @param put writes one history row
 * @param baseUrlOf the base URL of the server that owns a profile's data
 */
class ReadingHistoryRecorder(
    scope: CoroutineScope? = null,
    private val put: suspend (Archive) -> Unit = { ServiceRegistry.dataModule.historyRepository.putHistoryInfo(it) },
    private val baseUrlOf: suspend (profileId: Long) -> String = {
        resolveSourceBaseUrl(it, ServiceRegistry.dataModule.profileLookupCache)
    },
) {

    private val scope: CoroutineScope by lazy { scope ?: ServiceRegistry.coroutineModule.ioScope }

    fun record(archive: Archive?, tankSeed: TankSessionSeed?) {
        archive?.let {
            scope.launch {
                try {
                    put(it)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record history: ${e.message}")
                }
            }
        }
        tankSeed?.let { seed ->
            scope.launch {
                try {
                    put(tankEntry(seed, baseUrlOf(seed.profileId)))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record tank history: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "GalleryActivity"

        /** The app's instance, on the process-wide IO scope. */
        val default: ReadingHistoryRecorder by lazy { ReadingHistoryRecorder() }

        /** The tank session's history row: the tank as one archive of all its members' pages. */
        internal fun tankEntry(seed: TankSessionSeed, baseUrl: String): Archive = Archive(
            arcid = seed.tankId,
            title = seed.tankName,
            tags = emptyMap(),
            pagecount = seed.members.sumOf { it.pagecount.coerceAtLeast(0) },
            progress = 0,
            extension = "",
            filename = "",
            thumbnailUrl = LRRTankoubonApi.getTankoubonThumbnailUrl(baseUrl, seed.tankId),
            rating = 0f,
            isnew = false,
            lastreadtime = 0L,
            summary = null,
            serverProfileId = seed.profileId,
        )
    }
}
