package com.lanraragi.reader.ui.scene.gallery.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanraragi.reader.archive.MetadataPluginService
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.MetadataPlugin
import com.lanraragi.reader.domain.PluginTagChanges
import com.lanraragi.reader.domain.PluginTagSuggestion
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerConnections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why a step of the plugin flow stopped. */
sealed interface PluginTagFailure {
    /** The server wants a key this profile does not have, or refused the one it has (401). */
    object KeyRequired : PluginTagFailure
    object Unsupported : PluginTagFailure
    object TimedOut : PluginTagFailure

    /** The archive is locked on the server (HTTP 423). */
    object Busy : PluginTagFailure
    class PluginError(val message: String?) : PluginTagFailure
    class Other(val cause: Exception) : PluginTagFailure
}

data class ReviewTag(val tag: String, val checked: Boolean = true)

/** A proposed replacement. Unchecked by default: it overwrites what the archive has. */
data class ReviewText(val old: String, val new: String, val checked: Boolean = false)

sealed interface PluginTagUiState {
    object Loading : PluginTagUiState
    class Plugins(val plugins: List<MetadataPlugin>) : PluginTagUiState
    class Running(val plugin: MetadataPlugin) : PluginTagUiState

    /** The only state a write can start from, and only through [PluginTagViewModel.apply]. */
    data class Review(
        val tags: List<ReviewTag>,
        val title: ReviewText?,
        val summary: ReviewText?,
        val applying: Boolean = false,
        val applyFailure: PluginTagFailure? = null,
    ) : PluginTagUiState {
        val hasProposals: Boolean get() = tags.isNotEmpty() || title != null || summary != null
        val changes: PluginTagChanges
            get() = PluginTagChanges(
                tags = tags.filter { it.checked }.map { it.tag },
                title = title?.takeIf { it.checked }?.new,
                summary = summary?.takeIf { it.checked }?.new,
            )
        val changeCount: Int
            get() = changes.let { it.tags.size + (if (it.title != null) 1 else 0) + (if (it.summary != null) 1 else 0) }
    }

    data class Failed(val failure: PluginTagFailure, val canRetry: Boolean) : PluginTagUiState

    /** The flow ended. [wrote] is false when the server already held everything confirmed. */
    data class Applied(val wrote: Boolean) : PluginTagUiState
}

/**
 * The plugin screen's flow for one archive: choose a plugin, wait for its run,
 * review the proposal. All of it survives a rotation here; leaving the screen
 * or going back from the review discards it without writing anything.
 */
class PluginTagViewModel(
    private val service: MetadataPluginService = MetadataPluginService(),
    private val connectionFor: suspend (Archive) -> ServerConnection = { ServerConnections.forArchive(it) },
) : ViewModel() {

    private val _state = MutableStateFlow<PluginTagUiState>(PluginTagUiState.Loading)
    val state: StateFlow<PluginTagUiState> = _state.asStateFlow()

    private var archive: Archive? = null
    private var conn: ServerConnection? = null
    private var plugins: List<MetadataPlugin>? = null
    private var work: Job? = null
    private var retryLast: (() -> Unit)? = null

    /** First call wins, so a recreated view keeps the flow it left. */
    fun init(archive: Archive) {
        if (this.archive != null) return
        this.archive = archive
        load()
    }

    fun retry() {
        retryLast?.invoke()
    }

    private fun load() {
        val archive = archive ?: return
        retryLast = ::load
        _state.value = PluginTagUiState.Loading
        work?.cancel()
        work = viewModelScope.launch {
            _state.value = try {
                val list = service.plugins(connection(archive))
                plugins = list
                PluginTagUiState.Plugins(list)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    /** Starts one run. Nothing is written by it, whatever it returns. */
    fun run(plugin: MetadataPlugin, arg: String?) {
        val archive = archive ?: return
        retryLast = { run(plugin, arg) }
        _state.value = PluginTagUiState.Running(plugin)
        work?.cancel()
        work = viewModelScope.launch {
            _state.value = try {
                when (val outcome = service.run(connection(archive), archive.arcid, plugin, arg)) {
                    is MetadataPluginService.RunOutcome.Suggested -> review(archive, outcome.suggestion)
                    is MetadataPluginService.RunOutcome.Failed ->
                        PluginTagUiState.Failed(PluginTagFailure.PluginError(outcome.message), canRetry = true)
                    MetadataPluginService.RunOutcome.TimedOut ->
                        PluginTagUiState.Failed(PluginTagFailure.TimedOut, canRetry = true)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                failed(e)
            }
        }
    }

    private fun review(archive: Archive, suggestion: PluginTagSuggestion) = PluginTagUiState.Review(
        tags = suggestion.newTags.map { ReviewTag(it) },
        title = suggestion.title?.takeIf { it != archive.title }?.let { ReviewText(archive.title, it) },
        summary = suggestion.summary?.takeIf { it != archive.summary }?.let { ReviewText(archive.summary.orEmpty(), it) },
    )

    fun setTagChecked(tag: String, checked: Boolean) = updateReview { review ->
        review.copy(tags = review.tags.map { if (it.tag == tag) it.copy(checked = checked) else it })
    }

    fun setTitleChecked(checked: Boolean) = updateReview { it.copy(title = it.title?.copy(checked = checked)) }

    fun setSummaryChecked(checked: Boolean) = updateReview { it.copy(summary = it.summary?.copy(checked = checked)) }

    private inline fun updateReview(change: (PluginTagUiState.Review) -> PluginTagUiState.Review) {
        val review = _state.value as? PluginTagUiState.Review ?: return
        if (!review.applying) _state.value = change(review)
    }

    /** The user's confirmation: the one call in this flow that writes. A failure keeps the review. */
    fun apply() {
        val review = _state.value as? PluginTagUiState.Review ?: return
        val archive = archive ?: return
        if (review.applying || review.changes.isEmpty) return
        _state.value = review.copy(applying = true, applyFailure = null)
        work = viewModelScope.launch {
            _state.value = try {
                PluginTagUiState.Applied(service.apply(connection(archive), archive.arcid, review.changes))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                review.copy(applying = false, applyFailure = classify(e, notFoundIsUnsupported = false))
            }
        }
    }

    /** Whether back stays on this screen now, rather than leaving it. */
    fun backStaysInScene(): Boolean = when (val s = _state.value) {
        is PluginTagUiState.Review -> s.applying || plugins != null
        is PluginTagUiState.Running, is PluginTagUiState.Failed -> plugins != null
        else -> false
    }

    /**
     * One step back inside the screen: a running plugin is abandoned, a review
     * discarded. Back during the write itself does nothing; it is about to end.
     */
    fun back() {
        val list = plugins ?: return
        if ((_state.value as? PluginTagUiState.Review)?.applying == true) return
        work?.cancel()
        retryLast = ::load
        _state.value = PluginTagUiState.Plugins(list)
    }

    private suspend fun connection(archive: Archive): ServerConnection =
        conn ?: connectionFor(archive).also { conn = it }

    private fun failed(e: Exception): PluginTagUiState.Failed {
        val failure = classify(e, notFoundIsUnsupported = true)
        return PluginTagUiState.Failed(failure, canRetry = failure != PluginTagFailure.KeyRequired)
    }

    private fun classify(e: Exception, notFoundIsUnsupported: Boolean): PluginTagFailure = when {
        e !is LRRHttpException -> PluginTagFailure.Other(e)
        e.code == HTTP_UNAUTHORIZED -> PluginTagFailure.KeyRequired
        e.code == HTTP_LOCKED -> PluginTagFailure.Busy
        e.code == HTTP_NOT_FOUND && notFoundIsUnsupported -> PluginTagFailure.Unsupported
        else -> PluginTagFailure.Other(e)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_LOCKED = 423
    }
}
