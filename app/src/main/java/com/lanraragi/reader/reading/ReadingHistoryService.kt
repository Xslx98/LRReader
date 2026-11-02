package com.lanraragi.reader.reading

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.appwidget.ContinueReadingWidget
import com.lanraragi.reader.dao.HistoryInfo
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.server.LRRAuthManager
import com.lanraragi.reader.shortcuts.ContinueReadingShortcut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * The edits the history screen makes to the reading history: a rating,
 * removing entries, clearing the shown server's history.
 *
 * Each edit runs on the process scope, so it finishes even when the screen
 * that asked for it closes first (a batch removal is never cut in half);
 * the screen only awaits the outcome. A failed edit is logged here, since
 * its caller may be gone, and rethrown to a caller that is still waiting.
 *
 * @param scope the long-lived scope edits run on; null = the app's IO
 *   scope, resolved at the first edit
 * @param repository the history store; null = the data module's, resolved
 *   at each edit
 */
class ReadingHistoryService(
    private val scope: CoroutineScope? = null,
    private val repository: HistoryRepository? = null,
) {

    /** Stores [rating] in the history snapshot of [arcid] on [profileId]. */
    suspend fun updateRating(arcid: String, profileId: Long, rating: Float) =
        edit("Failed to persist history rating") { it.updateRating(arcid, profileId, rating) }

    /** Removes one history entry. */
    suspend fun delete(info: HistoryInfo) =
        edit("Failed to delete history item") { it.deleteHistoryInfo(info) }

    /**
     * Removes [archives] from the history one by one, so one failure does
     * not abort the rest. Returns (removed, failed).
     */
    suspend fun remove(archives: List<Archive>): Pair<Int, Int> = edit("Failed to batch-remove history") { repo ->
        var ok = 0
        var bad = 0
        for (archive in archives) {
            try {
                repo.deleteHistory(archive.arcid, archive.serverProfileId)
                ok++
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Failed to batch-remove a history entry", e)
                bad++
            }
        }
        ok to bad
    }

    /**
     * Clears the active server's history. Only that server's history is gone:
     * the launcher shortcut is dropped if it pointed there (issue #16) and the
     * widget re-renders from whatever history survives (issue #9).
     */
    suspend fun clearShownServer() = edit("Failed to clear history") { repo ->
        repo.clearHistory()
        val profileId = LRRAuthManager.getActiveProfileId()
        if (profileId > 0) {
            ContinueReadingShortcut.removeIfProfileSafely(profileId)
        } else {
            ContinueReadingShortcut.removeSafely()
        }
        ContinueReadingWidget.refreshSafely()
    }

    private suspend fun <T> edit(failure: String, block: suspend (HistoryRepository) -> T): T =
        (scope ?: ServiceRegistry.coroutineModule.ioScope).async {
            try {
                block(repository ?: ServiceRegistry.dataModule.historyRepository)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, failure, e)
                throw e
            }
        }.await()

    companion object {
        private const val TAG = "ReadingHistoryService"

        val default: ReadingHistoryService by lazy { ReadingHistoryService() }
    }
}
