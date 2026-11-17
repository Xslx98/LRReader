package com.lanraragi.reader.ui.scene.stats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.server.ServerConnections
import com.lanraragi.reader.server.excludedNamespaces
import com.lanraragi.reader.stats.ReadingStatsCalculator
import com.lanraragi.reader.stats.TagPreferenceCalculator
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.ui.list.ListScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/**
 * Loads the snapshot reading statistics (issue #18): cross-profile history
 * rows + profile names on IO, derived by the pure [ReadingStatsCalculator].
 * Offline by design: the only network touch is [defaultServerExcludedNamespaces],
 * a best-effort `/api/info` fired once per server when its
 * `excluded_namespaces` were never observed — failures degrade to the
 * built-in exclusion set, never to an error.
 *
 * @param serverExcludedNamespaces supplies the active server's admin-excluded
 *   tag namespaces for [TagPreferenceCalculator]; injectable so tests pin it.
 */
class ReadingStatsViewModel(
    private val serverExcludedNamespaces: suspend () -> Set<String> = ::defaultServerExcludedNamespaces,
) : ViewModel() {

    private val historyRepository = ServiceRegistry.dataModule.historyRepository
    private val profileRepository = ServiceRegistry.dataModule.profileRepository

    private val _state =
        MutableStateFlow<ListScreenState<ReadingStatsCalculator.ReadingStats>>(ListScreenState.Loading)

    /**
     * What the screen shows: one item, the stats, once anything was read
     * (Empty before that), or the load's error (it used to look empty).
     */
    val state: StateFlow<ListScreenState<ReadingStatsCalculator.ReadingStats>> = _state.asStateFlow()

    private fun on(event: ListScreenState.Event<ReadingStatsCalculator.ReadingStats>) {
        _state.update { ListScreenState.reduce(it, event) }
    }

    private val _tagPreference =
        MutableStateFlow<TagPreferenceCalculator.TagPreference?>(null)
    val tagPreference: StateFlow<TagPreferenceCalculator.TagPreference?> =
        _tagPreference.asStateFlow()

    fun load() {
        on(ListScreenState.Event.Load)
        viewModelScope.launch {
            try {
                val (rows, names, excluded) = withContext(Dispatchers.IO) {
                    val rows = historyRepository.getAllHistoryStatsRows()
                    val names = profileRepository.getAllProfiles()
                        .associate { it.id to it.name }
                    Triple(rows, names, serverExcludedNamespaces())
                }
                val stats = ReadingStatsCalculator.compute(rows, names)
                _tagPreference.value = TagPreferenceCalculator.compute(rows, excluded)
                on(ListScreenState.Event.Loaded(if (stats.totalArchives == 0) emptyList() else listOf(stats)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load reading stats", e)
                on(ListScreenState.Event.Failed(friendlyError(ServiceRegistry.appModule.getContext(), e)))
            }
        }
    }

    companion object {
        private const val TAG = "ReadingStatsViewModel"

        /** [excludedNamespaces] of the active server; no active server yields the empty set. */
        private suspend fun defaultServerExcludedNamespaces(): Set<String> {
            val conn = ServerConnections.active() ?: return emptySet()
            return excludedNamespaces(conn)
        }
    }
}
