package com.lanraragi.reader.ui.scene.gallery.detail

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.R
import com.lanraragi.reader.UrlOpener
import com.lanraragi.reader.client.LRRUrl
import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.dao.DownloadInfo
import com.lanraragi.reader.gallery.TankSessionRouter
import com.lanraragi.reader.mapper.toArchive
import com.lanraragi.reader.ui.CommonOperations
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.ui.MainActivity
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.download.DownloadLabelHelper
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene
import com.lanraragi.reader.util.ClipboardUtil
import com.lanraragi.framework.lib.yorozuya.AssertUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lanraragi.reader.download.DownloadState

/**
 * Handles action button clicks and popup menu for [GalleryDetailScene].
 *
 * Owns: onClick/onLongClick dispatch, download button, popup menu,
 * category dialog, tag navigation, title copy.
 */
internal class DetailActionHandler(
    private val scene: GalleryDetailScene,
    private val viewModel: GalleryDetailViewModel,
    private val lifecycleOwner: LifecycleOwner,
) {

    private var popupMenu: PopupMenu? = null

    // View references set by the scene after creation
    var otherActions: ImageView? = null
    var download: TextView? = null

    /**
     * Updates the download button text based on the current download state
     * from the ViewModel.
     */
    fun updateDownloadText() {
        val dl = download ?: return
        when (viewModel.downloadState.value) {
            DownloadState.NONE -> dl.setText(R.string.download_state_none)
            DownloadState.WAIT -> dl.setText(R.string.download_state_wait)
            DownloadState.DOWNLOAD -> dl.setText(R.string.download_state_downloading)
            DownloadState.FINISH -> dl.setText(R.string.download_state_downloaded)
            DownloadState.FAILED -> dl.setText(R.string.download_state_failed)
            else -> dl.setText(R.string.download)
        }
    }

    fun ensurePopMenu(context: Context) {
        if (popupMenu != null || otherActions == null) {
            return
        }

        val popup = PopupMenu(context, otherActions!!, Gravity.TOP)
        popupMenu = popup
        popup.menuInflater.inflate(R.menu.scene_gallery_detail, popup.menu)
        // Show LANraragi-specific menu items only when connected
        val isLrrConnected = LRRAuthManager.getServerUrl() != null
        val deleteItem = popup.menu.findItem(R.id.action_lrr_delete)
        deleteItem?.isVisible = isLrrConnected
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open_in_other_app -> {
                    val url = getGalleryDetailUrl()
                    val act = scene.activity2
                    if (url != null && act != null) {
                        UrlOpener.openUrl(act, url, false)
                    }
                }
                R.id.action_refresh -> {
                    scene.requestRefresh()
                }
                R.id.action_lrr_delete -> {
                    DeleteArchiveHelper.show(
                        scene.activity2,
                        viewModel.getEffectiveArchive(),
                        viewModel.getSourceProfileId(),
                    ) { title ->
                        scene.showTip(
                            scene.getString(R.string.lrr_delete_success, title),
                            BaseScene.LENGTH_LONG
                        )
                        scene.onBackPressed()
                    }
                }
            }
            true
        }
    }

    fun showPopMenu() {
        popupMenu?.show()
    }

    fun onClick(v: View, context: Context, activity: MainActivity?) {
        if (activity == null) return

        when {
            v === otherActions -> {
                ensurePopMenu(context)
                showPopMenu()
            }
            v.id == R.id.download -> {
                onDownloadClick(context, activity)
            }
            v.id == R.id.read -> {
                val archive = viewModel.getEffectiveArchive()
                if (archive != null) {
                    // A member reached through a tank detail flow reads in
                    // the whole-tank composite session; everything else
                    // stays a standalone per-archive session.
                    val tankIntent = TankSessionRouter.tankIntentFor(activity, archive.arcid)
                    if (tankIntent != null) {
                        scene.startActivity(tankIntent)
                        return
                    }
                    lifecycleOwner.lifecycleScope.launch {
                        try {
                            val intent = withContext(Dispatchers.IO) {
                                GalleryOpenHelper.buildReadIntent(activity, archive)
                            }
                            scene.startActivity(intent)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Failed to build read intent", e)
                        }
                    }
                }
            }
            v.id == R.id.heart_group -> {
                showCategoryDialog(activity)
            }
            v.id == R.id.title -> {
                val title = viewModel.archiveDetail.value?.archive?.title
                if (!title.isNullOrEmpty()) {
                    ClipboardUtil.copyText(title)
                    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                val tag = v.getTag(R.id.tag)
                if (tag is String) {
                    val lub = ListUrlBuilder()
                    lub.mode = ListUrlBuilder.MODE_TAG
                    lub.keyword = tag
                    GalleryListScene.startScene(scene, lub)
                }
            }
        }
    }

    fun onLongClick(v: View, context: Context, activity: MainActivity?): Boolean {
        if (activity == null) return false

        return when {
            v.id == R.id.download -> {
                onDownloadClick(context, activity)
                true
            }
            v.id == R.id.heart_group -> {
                showCategoryDialog(activity)
                true
            }
            else -> {
                val tag = v.getTag(R.id.tag) as? String
                if (tag != null) {
                    GalleryTagHelper.showTagDialog(scene, context, tag)
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Handles download button click: start a new download or show delete dialog.
     */
    private fun onDownloadClick(context: Context, activity: MainActivity) {
        val archive = viewModel.getEffectiveArchive() ?: return

        if (viewModel.downloadManager.getDownloadState(archive.arcid) == DownloadState.INVALID) {
            CommonOperations.startDownload(activity, archive, false)
        } else {
            DownloadLabelHelper.showDeleteDialog(context, archive) { deleteFiles ->
                DownloadLabelHelper.performDelete(archive, deleteFiles)
            }
        }
    }

    private fun showCategoryDialog(activity: android.app.Activity) {
        val arcid = viewModel.getEffectiveArcid() ?: return
        CategoryDialogHelper.showCategoryDialog(
            activity, arcid, viewModel.getSourceProfileId()
        ) { isFavorited, favoriteName ->
            viewModel.updateFavoriteState(FavoriteState(isFavorited, favoriteName))
            onFavoriteChanged?.invoke(arcid)
        }
    }

    /**
     * Callback invoked when favorite state changes after a category dialog.
     * The scene uses this to update the favorite heart drawable.
     */
    var onFavoriteChanged: ((arcid: String) -> Unit)? = null

    private fun getGalleryDetailUrl(): String? {
        // EH-style URL is used only by the "open in other app" share menu.
        // The leading numeric segment is meaningless for LRR archives so we
        // hard-code 0; the arcid is the part the receiving app actually
        // cares about.
        val arcid = viewModel.getEffectiveArcid() ?: return null
        return LRRUrl.getGalleryDetailUrl(0L, arcid, 0, false)
    }

    fun destroy() {
        popupMenu = null
        otherActions = null
        download = null
    }

    companion object {
        private const val TAG = "DetailActionHandler"
    }
}
