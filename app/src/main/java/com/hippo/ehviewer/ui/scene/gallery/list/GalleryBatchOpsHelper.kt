package com.hippo.ehviewer.ui.scene.gallery.list

import android.app.Activity
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.settings.PrivacySettings
import com.hippo.ehviewer.ui.scene.gallery.detail.CategoryDialogHelper
import com.lanraragi.reader.domain.Archive

/**
 * Drives the bottom batch action bar shown while the gallery list is in
 * multi-select mode: select-all, batch download / add-to-category /
 * clear-new / delete, the selected-count label, and run progress.
 *
 * The Scene owns the flow subscriptions ([onBatchProgress] / [onBatchResult]
 * are delegated in from view-lifecycle-scoped collectors) and the
 * [com.hippo.ehviewer.ui.scene.ListMultiSelectHelper] callbacks
 * ([onModeChanged] / [onCheckedChanged]).
 *
 * While a batch is running the four operation buttons are disabled and the
 * bar stays visible — even after choice mode exits — until the run finishes.
 */
internal class GalleryBatchOpsHelper(
    private val bar: View,
    private val callback: Callback,
) {

    interface Callback {
        val activity: Activity?
        val viewModel: GalleryListViewModel
        val downloadManager: DownloadManager
        fun selectedArchives(): List<Archive>
        fun activeProfileId(): Long
        fun isSelectionActive(): Boolean
        fun exitSelection()
        fun checkAllSelection()
        fun refreshList()
    }

    private val countView: TextView = bar.findViewById(R.id.batch_count)
    private val downloadButton: Button = bar.findViewById(R.id.batch_download)
    private val categoryButton: Button = bar.findViewById(R.id.batch_category)
    private val clearNewButton: Button = bar.findViewById(R.id.batch_clear_new)
    private val deleteButton: Button = bar.findViewById(R.id.batch_delete)

    /** Last count reported by the selection helper; restored after a run ends. */
    private var selectedCount = 0

    init {
        bar.findViewById<Button>(R.id.batch_select_all)
            .setOnClickListener { callback.checkAllSelection() }
        downloadButton.setOnClickListener { onDownloadClick() }
        categoryButton.setOnClickListener { onCategoryClick() }
        clearNewButton.setOnClickListener { onClearNewClick() }
        deleteButton.setOnClickListener { onDeleteClick() }
    }

    // ─── ListMultiSelectHelper callbacks ─────────────────────────────────

    fun onModeChanged(active: Boolean) {
        if (active) {
            bar.visibility = View.VISIBLE
        } else if (!isRunning()) {
            bar.visibility = View.GONE
        }
        // else: a batch is in flight — keep the bar visible showing progress
        // until onBatchProgress(null) ends the run.
    }

    fun onCheckedChanged(count: Int) {
        selectedCount = count
        if (!isRunning()) {
            countView.text = bar.context.getString(R.string.batch_selected_count, count)
        }
    }

    // ─── ViewModel flow delegates (view-lifecycle-scoped in the Scene) ───

    fun onBatchProgress(progress: Pair<Int, Int>?) {
        setOpButtonsEnabled(progress == null)
        if (progress != null) {
            bar.visibility = View.VISIBLE
            countView.text =
                bar.context.getString(R.string.batch_running, progress.first, progress.second)
        } else if (callback.isSelectionActive()) {
            countView.text = bar.context.getString(R.string.batch_selected_count, selectedCount)
        } else {
            bar.visibility = View.GONE
        }
    }

    fun onBatchResult(result: GalleryListViewModel.BatchResult) {
        val activity = callback.activity ?: return
        // AlertDialog shows either a message or a list, not both, so the
        // failure lines are folded into the summary message.
        val message = StringBuilder(
            activity.getString(
                R.string.batch_result_summary, result.succeeded.size, result.failed.size
            )
        )
        if (result.failed.isNotEmpty()) {
            message.append("\n\n")
                .append(activity.getString(R.string.batch_result_failures_title))
                .append(':')
            result.failed.forEach {
                message.append('\n').append(it.title).append(" — ").append(it.reason)
            }
        }
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        if (result.op == GalleryListViewModel.BatchOp.ClearNew) {
            // NEW badges render from server data; reload so they disappear.
            callback.refreshList()
        }
        // DeleteArchives rows already fall out via AppEventBus.archiveDeletedEvent.
    }

    // ─── Operations ──────────────────────────────────────────────────────

    private fun onDownloadClick() {
        val selected = takeSelection() ?: return
        callback.viewModel.runBatchDownload(selected, callback.downloadManager)
        callback.exitSelection()
    }

    private fun onCategoryClick() {
        // Capture the selection now: the picker is async (network load) and
        // the selection is exited underneath it.
        val selected = takeSelection() ?: return
        CategoryDialogHelper.pickStaticCategory(
            callback.activity, callback.activeProfileId()
        ) { categoryId ->
            callback.viewModel.runBatch(
                GalleryListViewModel.BatchOp.AddToCategory(categoryId), selected
            )
        }
        callback.exitSelection()
    }

    private fun onClearNewClick() {
        val selected = takeSelection() ?: return
        callback.viewModel.runBatch(GalleryListViewModel.BatchOp.ClearNew, selected)
        callback.exitSelection()
    }

    private fun onDeleteClick() {
        // Capture before the confirm dialog: selection may change under it.
        val selected = takeSelection() ?: return
        val activity = callback.activity ?: return
        showDeleteConfirmDialog(activity, selected)
    }

    /**
     * Two-stage destructive confirm mirroring
     * [com.hippo.ehviewer.ui.scene.gallery.detail.DeleteArchiveHelper]: red
     * positive button, initially disabled behind a countdown (skippable via
     * [PrivacySettings.getDeleteConfirmCountdown]). The timer is held so early
     * dismissal (cancel / back / teardown) cancels it instead of leaking the
     * activity and firing on a detached button.
     */
    private fun showDeleteConfirmDialog(activity: Activity, selected: List<Archive>) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_delete_confirm_title)
            .setMessage(activity.getString(R.string.batch_delete_confirm_message, selected.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lrr_delete_confirm_button, null)
            .create()

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
                            (millisUntilFinished / COUNTDOWN_INTERVAL).toInt() + 1
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
                callback.viewModel.runBatch(
                    GalleryListViewModel.BatchOp.DeleteArchives, selected
                )
                callback.exitSelection()
            }
        }

        dialog.setOnDismissListener {
            countdownTimer?.cancel()
            countdownTimer = null
        }

        dialog.show()
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private fun isRunning(): Boolean = callback.viewModel.batchProgress.value != null

    /** Common op-entry guard: no-op while a run is in flight or on empty selection. */
    private fun takeSelection(): List<Archive>? {
        if (isRunning()) return null
        return callback.selectedArchives().takeIf { it.isNotEmpty() }
    }

    private fun setOpButtonsEnabled(enabled: Boolean) {
        downloadButton.isEnabled = enabled
        categoryButton.isEnabled = enabled
        clearNewButton.isEnabled = enabled
        deleteButton.isEnabled = enabled
    }

    private companion object {
        const val COUNTDOWN_MILLIS = 3000L
        const val COUNTDOWN_INTERVAL = 1000L
    }
}
