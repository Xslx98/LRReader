package com.lanraragi.reader.ui.scene.download

import androidx.lifecycle.LifecycleOwner
import com.lanraragi.reader.util.collectFlow

/**
 * The Downloads page's view-scoped collectors: load errors, the structural
 * list, live progress, filter / sort / search state and the download
 * manager's listener events.
 */
class DownloadsEvents(
    private val scene: DownloadsScene,
    private val viewModel: DownloadsViewModel,
) {

    fun install(owner: LifecycleOwner) {
        // A failed load from the database must not look like "no downloads":
        // the tip shows the error and retries on tap (audit 2026-10-04 C34).
        collectFlow(owner, viewModel.downloadManager.loadError) { error ->
            scene.loadError = error
            scene.updateView()
        }

        // Subscribe to the structural list StateFlow (Room Flow, label-
        // filtered). Transient progress flows separately via progressMap
        // below. DiffUtil compares structural fields only.
        collectFlow(owner, viewModel.downloadList) { list ->
            if (!scene.hasAdapter || scene.searching) return@collectFlow
            scene.listDispatcher.dispatchDiffUpdate(ArrayList(list))
            scene.updateTitle()
            scene.updatePaginationIndicator()
            scene.updateView()
        }

        // Subscribe to the live progress map. For each arcid whose snapshot
        // changed since the last emission, fire a targeted
        // notifyItemChanged(pos, PAYLOAD_PROGRESS) so only the progress views
        // repaint — no DiffUtil, no full rebind. Fixes the progress-freeze
        // bug introduced by the earlier mutating-combine approach.
        collectFlow(owner, viewModel.progressMap) { progressMap ->
            if (!scene.hasAdapter || scene.searching) return@collectFlow
            scene.listDispatcher.dispatchProgressChanges(progressMap)
        }

        // Observe filter loading state
        collectFlow(owner, viewModel.filterLoading) { loading ->
            scene.showFilterLoading(loading)
        }

        // Observe filter/sort/search completion
        collectFlow(owner, viewModel.filterSearchDone) {
            if (!scene.isAdded) return@collectFlow
            scene.updateAdapter()
            // The downloadList collector skips updates while searching, so the
            // count in the title and the empty view must be refreshed here.
            scene.updateTitle()
            scene.updatePaginationIndicator()
            scene.updateView()
        }

        // ── Observe DownloadInfoListener events from ViewModel (sealed dispatch) ──

        collectFlow(owner, viewModel.downloadEvent) { event ->
            when (event) {
                // Structural events are covered by the downloadList Flow
                // above; transient progress by the progressMap Flow. These
                // listener callbacks exist only so downstream consumers
                // (e.g. the rating sync path inside GalleryDetailViewModel)
                // can react — the Scene itself just refreshes its empty-view.
                is DownloadUiEvent.ItemAdded,
                is DownloadUiEvent.ItemRemoved,
                is DownloadUiEvent.DiffUpdate,
                is DownloadUiEvent.Reloaded,
                is DownloadUiEvent.ItemUpdated -> {
                    scene.updateView()
                }
                is DownloadUiEvent.Replaced -> {
                    scene.updateForLabel()
                    scene.updateView()
                }
                is DownloadUiEvent.LabelRenamed -> {
                    viewModel.handleLabelRenamed(event.from, event.to)
                    scene.updateForLabel()
                    scene.updateView()
                }
                is DownloadUiEvent.LabelDeleted -> {
                    viewModel.resetToDefaultLabel()
                    scene.updateForLabel()
                    scene.updateView()
                }
                is DownloadUiEvent.LabelsChanged -> {
                    scene.updateDownloadLabels()
                }
            }
        }
    }
}
