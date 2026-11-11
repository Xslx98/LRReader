package com.lanraragi.reader.ui.scene.gallery.detail

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.archive.ListUrlBuilder
import com.lanraragi.reader.server.ServerSession
import com.lanraragi.reader.reading.TankSessionRouter
import com.lanraragi.reader.ui.CommonOperations
import com.lanraragi.reader.ui.GalleryOpenHelper
import com.lanraragi.reader.ui.MainActivity
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.ui.scene.download.DownloadLabelHelper
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene
import com.lanraragi.reader.util.ClipboardUtil
import com.lanraragi.reader.util.suspendRunCatching
import com.lanraragi.reader.widget.effectiveSourceProfileId
import com.lanraragi.reader.widget.resolveSourceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lanraragi.reader.dao.DownloadState
import com.lanraragi.reader.domain.FavoriteState

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
     * from the ViewModel. A download row made through another server profile
     * (one row per arcid, shared by mirrors — #104) carries that profile's
     * name, so the state is not mistaken for this server's own download.
     */
    fun updateDownloadText() {
        val dl = download ?: return
        val stateRes = when (viewModel.downloadState.value) {
            DownloadState.NONE -> R.string.download_state_none
            DownloadState.WAIT -> R.string.download_state_wait
            DownloadState.DOWNLOAD -> R.string.download_state_downloading
            DownloadState.FINISH -> R.string.download_state_downloaded
            DownloadState.FAILED -> R.string.download_state_failed
            else -> {
                dl.setText(R.string.download)
                return
            }
        }
        val stateText = dl.context.getString(stateRes)
        val label = currentSourceLabel(dl.context)
        dl.text = if (label == null) stateText else "$stateText · $label"
    }

    private fun currentSourceLabel(context: Context): String? {
        val arcid = viewModel.getEffectiveArcid() ?: return null
        val row = viewModel.downloadManager.getDownloadInfo(arcid) ?: return null
        return downloadSourceLabel(
            row.serverProfileId,
            viewModel.getSourceProfileId(),
            ServerSession.getActiveProfileId(),
            context.getString(R.string.lrr_download_source_deleted),
        ) { resolveSourceProfile(it)?.name }
    }

    fun ensurePopMenu(context: Context) {
        if (popupMenu != null || otherActions == null) {
            return
        }

        val popup = PopupMenu(context, otherActions!!, Gravity.TOP)
        popupMenu = popup
        popup.menuInflater.inflate(R.menu.scene_gallery_detail, popup.menu)
        // Show LANraragi-specific menu items only when connected
        val isLrrConnected = ServerSession.getServerUrl() != null
        val deleteItem = popup.menu.findItem(R.id.action_lrr_delete)
        deleteItem?.isVisible = isLrrConnected
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    scene.requestRefresh()
                }
                R.id.action_lrr_delete -> {
                    val activity = scene.activity2
                    DeleteArchiveHelper.show(
                        activity,
                        viewModel.getEffectiveArchive(),
                        viewModel.getSourceProfileId(),
                    ) { title ->
                        // show() returns early without an activity, so it is set here.
                        if (activity != null) {
                            deliverDeleteSuccess(scene, title, activity) {
                                activity.showTip(it, BaseScene.LENGTH_LONG)
                            }
                        }
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
                    val tankIntent = TankSessionRouter.tankLaunchFor(activity, archive.arcid)
                        ?.let { GalleryOpenHelper.buildTankReadIntent(activity, it) }
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
                    ClipboardUtil.copyText(context, title)
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
     * Handles download button click: start a new download or show the
     * delete dialog — unless the archive is a member of a DOWNLOADED
     * tankoubon (group-row truth, spec 2026-09-21 §6): a bound volume is
     * deleted as a whole from the downloads list, never one member at a
     * time, so the click is blocked with a pointer instead. The claim is
     * checked on every profile: the download row is shared by mirrors, so
     * this page must not delete a member of a tank downloaded through
     * another profile (#104). Starting a download (INVALID
     * state) is always allowed — it just fills the tank.
     */
    private fun onDownloadClick(context: Context, activity: MainActivity) {
        val archive = viewModel.getEffectiveArchive() ?: return

        if (viewModel.downloadManager.getDownloadState(archive.arcid) == DownloadState.INVALID) {
            CommonOperations.startDownload(activity, archive, false)
            return
        }
        lifecycleOwner.lifecycleScope.launch {
            val claiming = withContext(Dispatchers.IO) {
                suspendRunCatching {
                    ServiceRegistry.dataModule.downloadDbRepository.findTankGroupClaiming(archive.arcid)
                }.getOrNull()
            }
            if (claiming != null) {
                AlertDialog.Builder(context)
                    .setMessage(context.getString(R.string.tank_member_delete_blocked, claiming.name))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            DownloadLabelHelper.showDeleteDialog(context, archive) { deleteFiles ->
                viewModel.downloadManager.deleteDownloadAndFiles(archive.arcid, deleteFiles)
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

    fun destroy() {
        popupMenu = null
        otherActions = null
        download = null
    }

    companion object {
        private const val TAG = "DetailActionHandler"

        /**
         * Source label for the download button (#104), or null for none.
         * A row downloaded through the page's own profile needs no label;
         * a legacy row (0) is attributed to the active profile, as
         * [com.lanraragi.reader.widget.bindSourceServerBadge] does, and
         * shows none either. A page without a source id (0) is the active
         * profile's. [nameOf] gives a live profile's name; a deleted one
         * reads [deletedText].
         */
        @JvmStatic
        internal fun downloadSourceLabel(
            rowProfileId: Long,
            pageProfileId: Long,
            activeProfileId: Long?,
            deletedText: String,
            nameOf: (Long) -> String?,
        ): String? {
            if (rowProfileId == 0L) return null
            if (rowProfileId == effectiveSourceProfileId(pageProfileId, activeProfileId)) return null
            return nameOf(rowProfileId) ?: deletedText
        }

        /**
         * Lands a finished server delete (audit 2026-10-06 N4). The DELETE runs
         * on the app scope, so the user may have left the detail scene before it
         * returns: a removed scene has no context (getString/showTip would throw
         * IllegalStateException on Main) and must not finish anything. Only a
         * scene still added shows the tip and closes itself; otherwise the
         * success is reported through the (still alive) host activity.
         */
        @JvmStatic
        internal fun deliverDeleteSuccess(
            scene: BaseScene,
            title: String,
            hostContext: Context,
            hostTip: (CharSequence) -> Unit,
        ) {
            if (scene.isAdded) {
                scene.showTip(
                    scene.getString(R.string.lrr_delete_success, title),
                    BaseScene.LENGTH_LONG
                )
                scene.onBackPressed()
            } else {
                hostTip(hostContext.getString(R.string.lrr_delete_success, title))
            }
        }
    }
}
