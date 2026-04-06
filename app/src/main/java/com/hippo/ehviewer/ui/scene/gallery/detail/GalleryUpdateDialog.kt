package com.hippo.ehviewer.ui.scene.gallery.detail

import android.app.AlertDialog
import android.content.Context
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.util.ClipboardUtil.createAnnouncerFromClipboardUrl

class GalleryUpdateDialog(
    val detailScene: GalleryDetailScene,
    val context: Context
) {
    private var galleryDetail: GalleryDetail? = null
    private var dialog: AlertDialog? = null
    private var choseDialog: AlertDialog? = null

    @JvmField
    var autoDownload: Boolean = false

    fun showSelectDialog(galleryDetail: GalleryDetail) {
        if (galleryDetail === this.galleryDetail && dialog != null) {
            dialog!!.setTitle(R.string.new_version)
            dialog!!.show()
        }
        this.galleryDetail = galleryDetail
        val builder = AlertDialog.Builder(context)
        builder.setSingleChoiceItems(galleryDetail.getUpdateVersionName(), -1) { _, index ->
            dialog!!.dismiss()
            val announcer = createAnnouncerFromClipboardUrl(galleryDetail.newVersions!![index].versionUrl)
            detailScene.startScene(announcer)
        }
        dialog = builder.create()
        dialog!!.setTitle(R.string.new_version)
        dialog!!.show()
    }

    private fun showChooseDialog(url: String) {
        if (choseDialog != null) {
            choseDialog!!.show()
        }
        val builder = AlertDialog.Builder(context)
        choseDialog = builder.setTitle(R.string.gallery_update_dialog_title)
            .setMessage(R.string.gallery_update_dialog_message)
            .setNeutralButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(R.string.gallery_update_download_as_new) { _, _ ->
                autoDownload = true
                detailScene.startDownloadAsNew(url)
            }
            .setPositiveButton(R.string.gallery_update_override_old) { _, _ ->
                detailScene.startUpdateDownload(url)
            }
            .create()
        choseDialog!!.show()
    }
}
