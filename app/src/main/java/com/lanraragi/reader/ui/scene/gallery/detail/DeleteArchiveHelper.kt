package com.lanraragi.reader.ui.scene.gallery.detail

import android.app.Activity
import android.os.CountDownTimer
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.lanraragi.reader.R
import com.lanraragi.reader.archive.ArchiveDeletion
import com.lanraragi.reader.settings.PrivacySettings
import com.lanraragi.reader.domain.Archive

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

    /**
     * @param serverProfileId the source profile that owns [archive], sourced
     *   from the VM's authoritative `_archive` (Room SSOT), NOT from
     *   `getEffectiveArchive()`. The delete is routed to that server so a
     *   cross-server archive is not deleted from the wrong (active) one —
     *   LANraragi arcids are content-hash based, so the same file can exist
     *   on multiple servers under the same id.
     */
    @JvmStatic
    fun show(activity: Activity?, archive: Archive?, serverProfileId: Long, callback: Callback?) {
        if (activity == null || archive == null) return

        val title = archive.title.ifEmpty { activity.getString(R.string.lrr_unknown_title) }
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
            positiveButton.setTextColor(
                ContextCompat.getColor(activity, R.color.destructive_action)
            )

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
                performDelete(activity, arcid, title, serverProfileId, callback)
            }
        }

        dialog.setOnDismissListener {
            countdownTimer?.cancel()
            countdownTimer = null
        }

        dialog.show()
    }

    private fun performDelete(
        activity: Activity,
        arcid: String,
        title: String,
        serverProfileId: Long,
        callback: Callback?,
    ) {
        if (arcid.isEmpty()) return

        // ArchiveDeletion runs on a process scope: a rotation cannot cancel
        // the DELETE before the deleted event is posted.
        ArchiveDeletion.default.deleteAsync(serverProfileId, arcid) { result ->
            val error = result.exceptionOrNull()
            if (error != null) Log.e("DeleteArchiveHelper", "Delete archive failed", error)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (error == null) {
                    callback?.onDeleteSuccess(title)
                } else {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.lrr_delete_failed, error.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
