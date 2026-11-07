package com.lanraragi.reader.ui.scene.stats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.server.ServerConnections
import com.lanraragi.reader.server.excludedNamespaces
import com.lanraragi.reader.stats.ReadingStatsCalculator
import com.lanraragi.reader.stats.TagPreferenceCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val _stats = MutableStateFlow<ReadingStatsCalculator.ReadingStats?>(null)
    val stats: StateFlow<ReadingStatsCalculator.ReadingStats?> = _stats.asStateFlow()

    private val _tagPreference =
        MutableStateFlow<TagPreferenceCalculator.TagPreference?>(null)
    val tagPreference: StateFlow<TagPreferenceCalculator.TagPreference?> =
        _tagPreference.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val (rows, names, excluded) = withContext(Dispatchers.IO) {
                    val rows = historyRepository.getAllHistoryStatsRows()
                    val names = profileRepository.getAllProfiles()
                        .associate { it.id to it.name }
                    Triple(rows, names, serverExcludedNamespaces())
                }
                _stats.value = ReadingStatsCalculator.compute(rows, names)
                _tagPreference.value = TagPreferenceCalculator.compute(rows, excluded)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load reading stats", e)
                _stats.value = ReadingStatsCalculator.compute(emptyList(), emptyMap())
                _tagPreference.value = TagPreferenceCalculator.compute(emptyList())
            } finally {
                _isLoading.value = false
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
