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
 * download service" action, or one archive to show — and the list position
 * held until the list view exists.
 */
class DownloadDeepLink(
    private val scene: DownloadsScene,
    private val viewModel: DownloadsViewModel,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var initPosition = -1

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
        // are asserted to run). isListViewCreated already guards the case where
        // the view isn't built yet (stashes initPosition).
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
            viewModel.selectLabel(info.label)
            scene.updateForLabel()
            scene.updateView()

            val list = scene.list
            if (list != null) {
                val position = list.indexOf(info)
                if (position >= 0 && scene.isListViewCreated) {
                    scene.showListPosition(position, scroll = false)
                } else {
                    initPosition = position
                }
            }
        }
    }

    /** Applies a position that arrived before the list view existed; called from onCreateView3. */
    fun restorePendingPosition() {
        if (initPosition >= 0 && viewModel.indexPage.value != 1) {
            scene.showListPosition(initPosition, scroll = true)
            initPosition = -1
        }
    }

    private companion object {
        val TAG: String = DownloadsScene::class.java.simpleName
    }
}
