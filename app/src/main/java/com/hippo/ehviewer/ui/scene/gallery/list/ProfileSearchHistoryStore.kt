package com.hippo.ehviewer.ui.scene.gallery.list

import android.util.Log
import com.hippo.ehviewer.dao.SearchHistoryRepository
import com.hippo.ehviewer.widget.SearchBar
import com.lanraragi.reader.client.api.LRRAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bridges the synchronous [SearchBar.HistoryStore] suggestion path to the
 * suspend [SearchHistoryRepository] for the active profile.
 *
 * Reads serve from an in-memory snapshot of the profile's full retained
 * history (cap [SearchHistoryRepository.MAX_ENTRIES]); mutations update the
 * snapshot synchronously — the suggestion list repaints immediately after a
 * record/delete — and persist fire-and-forget on [scope]. DB failures leave
 * the snapshot ahead of disk until the next [refresh]; acceptable for
 * suggestion data.
 *
 * Call [refresh] when the backing truth may have changed underneath the
 * snapshot (view creation, resume after a possible profile switch).
 */
class ProfileSearchHistoryStore(
    private val scope: CoroutineScope,
    private val repository: SearchHistoryRepository,
    private val activeProfileId: () -> Long = { LRRAuthManager.getActiveProfileId() },
) : SearchBar.HistoryStore {

    private var cache: List<String> = emptyList()

    fun refresh() {
        val profileId = activeProfileId()
        scope.launch {
            try {
                cache = repository.recentSearches(profileId, SearchHistoryRepository.MAX_ENTRIES)
            } catch (e: Exception) {
                Log.w(TAG, "history refresh failed")
            }
        }
    }

    override fun recent(limit: Int): List<String> = cache.take(limit)

    override fun matching(prefix: String, limit: Int): List<String> =
        cache.asSequence()
            .filter { it.startsWith(prefix, ignoreCase = true) && it != prefix }
            .take(limit)
            .toList()

    override fun record(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        cache = listOf(q) + cache.filter { it != q }
        val profileId = activeProfileId()
        scope.launch {
            try {
                repository.recordSearch(profileId, q)
            } catch (e: Exception) {
                Log.w(TAG, "history record failed")
            }
        }
    }

    override fun delete(query: String) {
        cache = cache.filter { it != query }
        val profileId = activeProfileId()
        scope.launch {
            try {
                repository.deleteEntry(profileId, query)
            } catch (e: Exception) {
                Log.w(TAG, "history delete failed")
            }
        }
    }

    private companion object {
        const val TAG = "ProfileSearchHistory"
    }
}
