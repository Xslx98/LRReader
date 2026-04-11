package com.hippo.ehviewer.ui.scene.gallery.detail

import android.app.Activity
import android.graphics.Color
import android.os.CountDownTimer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryInfo
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRClientProvider
import com.lanraragi.reader.client.api.runSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Two-stage confirmation dialog for deleting an archive from the LANraragi server.
 * Stage 1: AlertDialog with warning text.
 * Stage 2: Confirm button has a 3-second countdown before it becomes clickable.
 */
object DeleteArchiveHelper {

    fun interface Callback {
        fun onDeleteSuccess(title: String)
    }

    @JvmStatic
    fun show(activity: Activity?, galleryInfo: GalleryInfo?, callback: Callback?) {
        if (activity == null || galleryInfo == null) return

        val title = galleryInfo.title ?: "Unknown"
        val arcid = galleryInfo.token

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_delete_confirm_title)
            .setMessage(activity.getString(R.string.lrr_delete_confirm_message, title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lrr_delete_confirm_button, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setTextColor(Color.parseColor("#F44336"))
            positiveButton.isEnabled = false

            object : CountDownTimer(3000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    positiveButton.text = activity.getString(
                        R.string.lrr_delete_countdown,
                        (millisUntilFinished / 1000).toInt() + 1
                    )
                }

                override fun onFinish() {
                    positiveButton.setText(R.string.lrr_delete_confirm_button)
                    positiveButton.isEnabled = true
                }
            }.start()

            positiveButton.setOnClickListener {
                dialog.dismiss()
                performDelete(activity, arcid, title, callback)
            }
        }

        dialog.show()
    }

    private fun performDelete(activity: Activity, arcid: String?, title: String, callback: Callback?) {
        if (arcid.isNullOrEmpty()) return

        (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
            try {
                runSuspend {
                    LRRArchiveApi.deleteArchive(
                        LRRClientProvider.getClient(),
                        LRRClientProvider.getBaseUrl(),
                        arcid
                    )
                }

                activity.runOnUiThread {
                    callback?.onDeleteSuccess(title)
                }
            } catch (e: Exception) {
                Log.e("DeleteArchiveHelper", "Delete archive failed", e)
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.lrr_delete_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
