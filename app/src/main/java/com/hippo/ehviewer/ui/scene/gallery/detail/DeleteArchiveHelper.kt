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
import com.hippo.ehviewer.event.AppEventBus
import com.hippo.ehviewer.event.ArchiveDeletedEvent
import com.hippo.ehviewer.settings.PrivacySettings
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.client.api.LRRClientProvider
import com.lanraragi.reader.client.api.runSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Two-stage confirmation dialog for deleting an archive from the LANraragi server.
 * Stage 1: AlertDialog with warning text.
 * Stage 2: Confirm button is initially disabled with a 3-second cooldown timer.
 *         The cooldown can be skipped via [PrivacySettings.getDeleteConfirmCountdown].
 */
object DeleteArchiveHelper {

    private const val COUNTDOWN_MILLIS = 3000L
    private const val COUNTDOWN_INTERVAL = 1000L

    fun interface Callback {
        fun onDeleteSuccess(title: String)
    }

    @JvmStatic
    fun show(activity: Activity?, archive: Archive?, callback: Callback?) {
        if (activity == null || archive == null) return

        val title = archive.title.ifEmpty { "Unknown" }
        val arcid = archive.arcid

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_delete_confirm_title)
            .setMessage(activity.getString(R.string.lrr_delete_confirm_message, title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lrr_delete_confirm_button, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setTextColor(Color.parseColor("#F44336"))

            if (PrivacySettings.getDeleteConfirmCountdown()) {
                positiveButton.isEnabled = false
                object : CountDownTimer(COUNTDOWN_MILLIS, COUNTDOWN_INTERVAL) {
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
            } else {
                positiveButton.isEnabled = true
                positiveButton.setText(R.string.lrr_delete_confirm_button)
            }

            positiveButton.setOnClickListener {
                dialog.dismiss()
                performDelete(activity, arcid, title, callback)
            }
        }

        dialog.show()
    }

    private fun performDelete(activity: Activity, arcid: String, title: String, callback: Callback?) {
        if (arcid.isEmpty()) return

        (activity as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
            try {
                runSuspend {
                    LRRArchiveApi.deleteArchive(
                        LRRClientProvider.getClient(),
                        LRRClientProvider.getBaseUrl(),
                        arcid
                    )
                }

                AppEventBus.postArchiveDeletedEvent(ArchiveDeletedEvent(arcid))
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
