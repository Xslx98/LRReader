package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.ChipGroup
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhCacheKeyFactory
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.GalleryInfoUi
import com.hippo.ehviewer.mapper.toEntity
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.GalleryOpenHelper
import com.hippo.ehviewer.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hippo.ehviewer.ui.dialog.SelectItemWithIconAdapter
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.util.AppHelper
import com.hippo.widget.LoadImageViewNew

/**
 * Handles item click and long-click context menu for GalleryListScene.
 * Extracted to reduce GalleryListScene's line count.
 */
class GalleryItemActionHelper(private val callback: Callback) {

    companion object {
        const val REQUEST_CODE_GALLERY_DETAIL = 100
    }

    interface Callback {
        fun getHostContext(): Context?
        fun getHostActivity(): Activity?
        fun getLayoutInflater(): LayoutInflater
        fun getDownloadManager(): DownloadManager
        fun getSceneFragment(): SceneFragment
        fun startScene(announcer: Announcer)
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String
        fun buildChipGroup(gi: GalleryInfoUi?, chipGroup: ChipGroup): ChipGroup
    }

    var alertDialog: AlertDialog? = null

    fun dismissDialog() {
        alertDialog?.dismiss()
    }

    fun onItemClick(view: View?, gi: GalleryInfoUi?): Boolean {
        if (gi == null) {
            return true
        }
        alertDialog?.dismiss()

        val args = android.os.Bundle()
        args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO)
        args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi.toEntity())
        val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
            .setRequestCode(callback.getSceneFragment(), REQUEST_CODE_GALLERY_DETAIL)
        if (view != null) {
            val thumb: View? = view.findViewById(R.id.thumb)
            if (thumb != null) {
                announcer.setTranHelper(EnterGalleryDetailTransaction(thumb))
            }
        }
        callback.startScene(announcer)
        return true
    }

    fun onItemLongClick(gi: GalleryInfoUi?, view: View): Boolean {
        val context = callback.getHostContext()
        val activity = callback.getHostActivity()
        if (context == null || activity == null) {
            return false
        }

        if (gi == null) {
            return true
        }

        val downloadManager = callback.getDownloadManager()
        val downloaded = downloadManager.getDownloadState(gi.gid) != DownloadInfo.STATE_INVALID
        val favourited = gi.favoriteSlot != -2

        val items = arrayOf<CharSequence>(
            context.getString(R.string.read),
            context.getString(if (downloaded) R.string.delete_downloads else R.string.download),
            context.getString(if (favourited) R.string.remove_from_favourites else R.string.add_to_favourites),
        )

        val icons = intArrayOf(
            R.drawable.v_book_open_x24,
            if (downloaded) R.drawable.v_delete_x24 else R.drawable.v_download_x24,
            if (favourited) R.drawable.v_heart_broken_x24 else R.drawable.v_heart_x24,
        )

        @SuppressLint("InflateParams")
        val linearLayout = callback.getLayoutInflater()
            .inflate(R.layout.gallery_item_dialog_coustom_title, null) as LinearLayout

        linearLayout.setOnClickListener { onItemClick(view, gi) }

        val imageViewNew: LoadImageViewNew = linearLayout.findViewById(R.id.dialog_thumb)
        imageViewNew.load(EhCacheKeyFactory.getThumbKey(gi.gid), gi.thumb)
        imageViewNew.setOnClickListener { onItemClick(view, gi) }

        callback.buildChipGroup(gi, linearLayout.findViewById(R.id.tab_tag_flow))

        val textView: TextView = linearLayout.findViewById(R.id.title_text)
        textView.text = EhUtils.getSuitableTitle(gi)
        textView.setOnClickListener {
            AppHelper.copyPlainText(EhUtils.getSuitableTitle(gi), context)
            val toast = Toast.makeText(context, R.string.lrr_title_copied, Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
        }

        alertDialog = AlertDialog.Builder(context)
            .setCustomTitle(linearLayout)
            .setAdapter(SelectItemWithIconAdapter(context, items, icons)) { _, which ->
                when (which) {
                    0 -> { // Read
                        ServiceRegistry.coroutineModule.ioScope.launch {
                            val intent = GalleryOpenHelper.buildReadIntent(activity, gi.toEntity())
                            withContext(Dispatchers.Main) {
                                activity.startActivity(intent)
                            }
                        }
                    }
                    1 -> { // Download
                        if (downloaded) {
                            AlertDialog.Builder(context)
                                .setTitle(R.string.download_remove_dialog_title)
                                .setMessage(callback.getString(R.string.download_remove_dialog_message, gi.title ?: ""))
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    downloadManager.deleteDownload(gi.gid)
                                }
                                .show()
                        } else {
                            (activity as? MainActivity)?.let {
                                CommonOperations.startDownload(it, gi.toEntity(), false)
                            }
                        }
                    }
                    2 -> { // Favorites
                        val entity = gi.toEntity()
                        if (favourited) {
                            CommonOperations.removeFromFavorites(
                                activity, entity,
                                RemoveFromFavoriteListener(activity)
                            )
                        } else {
                            CommonOperations.addToFavorites(
                                activity, entity,
                                AddToFavoriteListener(activity)
                            )
                        }
                    }
                }
            }.show()
        return true
    }

    private class AddToFavoriteListener(
        private val activity: Activity
    ) : CommonOperations.FavoriteListener {

        override fun onSuccess() {
            (activity as? MainActivity)?.showTip(R.string.add_to_favorite_success, BaseScene.LENGTH_SHORT)
        }

        override fun onFailure(e: Exception) {
            (activity as? MainActivity)?.showTip(R.string.add_to_favorite_failure, BaseScene.LENGTH_LONG)
        }
    }

    private class RemoveFromFavoriteListener(
        private val activity: Activity
    ) : CommonOperations.FavoriteListener {

        override fun onSuccess() {
            (activity as? MainActivity)?.showTip(R.string.remove_from_favorite_success, BaseScene.LENGTH_SHORT)
        }

        override fun onFailure(e: Exception) {
            (activity as? MainActivity)?.showTip(R.string.remove_from_favorite_failure, BaseScene.LENGTH_LONG)
        }
    }
}
