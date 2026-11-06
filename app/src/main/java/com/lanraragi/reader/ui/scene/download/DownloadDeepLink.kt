package com.lanraragi.reader.ui.scene.download

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.download.DownloadService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Arguments the Downloads page is opened with — a notification's "clear the
 * download service" action, or one archive to show once the list has it.
 */
class DownloadDeepLink(
    private val scene: DownloadsScene,
    private val viewModel: DownloadsViewModel,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val pendingJump = PendingArchiveJump()

    fun handle(args: Bundle?) {
        if (args == null) return

        if (DownloadsScene.ACTION_CLEAR_DOWNLOAD_SERVICE == args.getString(DownloadsScene.KEY_ACTION)) {
            DownloadService.Companion.clear()
        }

        val arcid = args.getString(DownloadsScene.KEY_ARCID) ?: return
        // The DownloadManager's in-memory list may still be loading on a cold
        // start (it initializes asynchronously). Reading getDownloadInfo before
        // that completes returns null and the deep link — e.g. tapping the
        // "download complete" notification — silently no-ops. Await init first,
        // then do the lookup on the main thread (where the manager's mutators
        // are asserted to run). A list view that isn't built yet leaves the jump
        // pending for the first list it shows.
        scene.lifecycleScope.launch {
            try {
                // awaitInitAsync asserts it is NOT on the main thread when init
                // is still pending (the case this whole block exists for), so
                // hop to IO for the wait; the lookup below resumes on Main.
                withContext(ioDispatcher) {
                    viewModel.downloadManager.awaitInitAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "awaitInitAsync failed; deep-link lookup skipped", e)
                return@launch
            }
            val info = viewModel.downloadManager.getDownloadInfo(arcid) ?: return@launch
            viewModel.selectLabel(viewModel.labelListingArchive(arcid, info.label))
            scene.updateForLabel()
            scene.updateView()

            pendingJump.request(arcid)
            if (scene.isListViewCreated) {
                pendingJump.resolveNow(viewModel::listIndexOfArchive)?.let(::show)
            }
        }
    }

    /** Called for each list the page shows. */
    fun onListShown() {
        val isEmpty = viewModel.downloadList.value.isEmpty()
        pendingJump.resolveOnNewList(isEmpty, viewModel::listIndexOfArchive)?.let(::show)
    }

    /** Checked rows are positions on the current page, so a selection keeps its page. */
    private fun show(position: Int) {
        if (scene.recyclerView?.isInCustomChoice != true) scene.showListPosition(position)
    }

    private companion object {
        val TAG: String = DownloadsScene::class.java.simpleName
    }
}
