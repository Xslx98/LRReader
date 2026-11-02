package com.lanraragi.reader.archive

import android.util.Log
import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.domain.mergeRatingIntoTags
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveRatingChangedEvent
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerConnections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes an archive's star rating to its source server as the `rating:` tag.
 * The write runs on [scope], a process scope, so leaving the detail page
 * never cuts it off after the screen has already shown the new value. Writes
 * are keyed by arcid: a newer rating of the SAME archive cancels the pending
 * one, ratings of other archives never touch each other.
 *
 * @param scope process scope the writes run on; null = the app's ioScope,
 *   resolved at first use
 * @param connections the server that owns data of a profile id
 * @param downloadRow mirrors a saved rating into the archive's download row,
 *   if it has one (main thread, through the download write queue)
 */
class ArchiveRatingWriter(
    scope: CoroutineScope? = null,
    private val connections: suspend (profileId: Long) -> ServerConnection = { ServerConnections.forProfile(it) },
    private val downloadRow: suspend (arcid: String, rating: Float) -> Unit = { arcid, rating ->
        withContext(Dispatchers.Main) {
            ServiceRegistry.dataModule.downloadManager.updateDownloadInfo(arcid) { it.rating = rating }
        }
    },
) {

    private val scope: CoroutineScope by lazy { scope ?: ServiceRegistry.coroutineModule.ioScope }

    private val pending = ConcurrentHashMap<String, Job>()

    /**
     * Fetches the archive's current tags (so edits made elsewhere survive),
     * PUTs them with [rating] merged in (0 = clear), then updates the download
     * row and posts [ArchiveRatingChangedEvent]. [onFailure] runs on [scope]
     * with the exception that stopped the write; a superseded write is
     * cancelled and reports nothing.
     */
    fun submit(profileId: Long, arcid: String, rating: Float, onFailure: suspend (Exception) -> Unit): Job {
        pending.remove(arcid)?.cancel()
        val job = scope.launch {
            try {
                val conn = connections(profileId)
                val serverArchive = LRRArchiveApi.getArchiveMetadata(conn.client, conn.baseUrl, arcid)
                val updatedTags = mergeRatingIntoTags(serverArchive.tags, rating)
                LRRArchiveApi.updateArchiveMetadata(conn.client, conn.baseUrl, arcid, updatedTags)
                downloadRow(arcid, rating)
                AppEventBus.postArchiveRatingChangedEvent(ArchiveRatingChangedEvent(arcid, rating))
                if (BuildConfig.DEBUG) Log.d(TAG, "Rating saved: $rating for $arcid")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Rating PUT failed; rolling back", e)
                onFailure(e)
            }
        }
        pending[arcid] = job
        job.invokeOnCompletion { pending.remove(arcid, job) }
        return job
    }

    companion object {
        private const val TAG = "ArchiveRatingWriter"

        /** The app's instance: one pending-write table for every detail page. */
        val default: ArchiveRatingWriter by lazy { ArchiveRatingWriter() }
    }
}
