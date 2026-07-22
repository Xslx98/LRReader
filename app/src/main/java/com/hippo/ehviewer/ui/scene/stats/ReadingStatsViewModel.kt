package com.hippo.ehviewer.ui.scene.stats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.stats.ReadingStatsCalculator
import com.hippo.ehviewer.stats.TagPreferenceCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads the snapshot reading statistics (issue #18): cross-profile history
 * rows + profile names on IO, derived by the pure [ReadingStatsCalculator].
 * Fully offline — no server requests.
 */
class ReadingStatsViewModel : ViewModel() {

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
                val (rows, names) = withContext(Dispatchers.IO) {
                    val rows = historyRepository.getAllHistoryStatsRows()
                    val names = profileRepository.getAllProfiles()
                        .associate { it.id to it.name }
                    rows to names
                }
                _stats.value = ReadingStatsCalculator.compute(rows, names)
                _tagPreference.value = TagPreferenceCalculator.compute(rows)
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
    }
}
