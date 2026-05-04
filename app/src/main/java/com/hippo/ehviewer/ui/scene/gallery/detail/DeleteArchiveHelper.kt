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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // Hold the active CountDownTimer so it can be cancelled when the dialog
        // dismisses early (cancel button, system back, activity teardown). Without
        // cancellation the timer keeps ticking up to COUNTDOWN_MILLIS while
        // capturing positiveButton + activity references, which leaks the
        // activity for up to 3s and then fires onFinish on a detached button.
        var countdownTimer: CountDownTimer? = null

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setTextColor(Color.parseColor("#F44336"))

            if (PrivacySettings.getDeleteConfirmCountdown()) {
                positiveButton.isEnabled = false
                countdownTimer = object : CountDownTimer(COUNTDOWN_MILLIS, COUNTDOWN_INTERVAL) {
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
                }.also { it.start() }
            } else {
                positiveButton.isEnabled = true
                positiveButton.setText(R.string.lrr_delete_confirm_button)
            }

            positiveButton.setOnClickListener {
                countdownTimer?.cancel()
                dialog.dismiss()
                performDelete(activity, arcid, title, callback)
            }
        }

        dialog.setOnDismissListener {
            countdownTimer?.cancel()
            countdownTimer = null
        }

        dialog.show()
    }

    private fun performDelete(activity: Activity, arcid: String, title: String, callback: Callback?) {
        if (arcid.isEmpty()) return

        val componentActivity = activity as ComponentActivity
        componentActivity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                runSuspend {
                    LRRArchiveApi.deleteArchive(
                        LRRClientProvider.getClient(),
                        LRRClientProvider.getBaseUrl(),
                        arcid
                    )
                }

                AppEventBus.postArchiveDeletedEvent(ArchiveDeletedEvent(arcid))
                withContext(Dispatchers.Main) {
                    if (!componentActivity.isFinishing && !componentActivity.isDestroyed) {
                        callback?.onDeleteSuccess(title)
                    }
                }
            } catch (ce: CancellationException) {
                // Lifecycle teardown cancelled the IO call; not a delete failure.
                throw ce
            } catch (e: Exception) {
                Log.e("DeleteArchiveHelper", "Delete archive failed", e)
                withContext(Dispatchers.Main) {
                    if (!componentActivity.isFinishing && !componentActivity.isDestroyed) {
                        Toast.makeText(
                            componentActivity,
                            componentActivity.getString(R.string.lrr_delete_failed, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}
