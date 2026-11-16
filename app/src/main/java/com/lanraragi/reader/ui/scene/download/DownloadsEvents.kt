package com.lanraragi.reader.ui.scene.download

import androidx.lifecycle.LifecycleOwner
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.ui.collectFlow

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
        collectFlow(owner, viewModel.downloadManager.loadState) { state ->
            scene.loadError = (state as? com.lanraragi.reader.download.DownloadLoadState.Failed)?.error
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
            scene.deepLink.onListShown()
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

        // Source badges read the profile snapshot, which is still empty when
        // the list binds at cold start; re-bind them when it lands and after
        // a profile rename or delete.
        collectFlow(owner, ServiceRegistry.dataModule.profileLookupCache.snapshot) {
            scene.listDispatcher.dispatchSourceBadges()
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

        // Rows, their state and progress come from the downloadList and
        // progressMap flows above (Room and the progress tracker); only the
        // label events have no row to carry them.
        collectFlow(owner, viewModel.downloadEvent) { event ->
            when (event) {
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
