package com.lanraragi.reader.ui.scene.gallery.list

import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.ui.scene.BaseScene

/**
 * Shows an archive upload's state on the gallery list: the determinate
 * progress dialog (mirrors the in-app update style) and the one-shot tips.
 */
class GalleryUploadProgressPresenter(private val scene: GalleryListScene) {

    private var dialog: AlertDialog? = null
    private var progressBar: LinearProgressIndicator? = null
    private var progressTitle: TextView? = null
    private var progressPercent: TextView? = null

    /**
     * Render the upload [state] into the determinate progress dialog and one-shot
     * tips. Terminal states reset the ViewModel back to Idle so they are not
     * replayed if the Scene re-subscribes (e.g. after a configuration change).
     */
    fun render(state: GalleryListViewModel.UploadUiState) {
        val viewModel = scene.viewModel
        when (state) {
            GalleryListViewModel.UploadUiState.Idle ->
                dismiss()

            GalleryListViewModel.UploadUiState.Preparing -> {
                show()
                progressTitle?.setText(R.string.lrr_upload_preparing)
                progressBar?.progress = 0
                progressPercent?.text = ""
            }

            is GalleryListViewModel.UploadUiState.Uploading -> {
                show()
                progressTitle?.setText(R.string.lrr_upload_uploading)
                progressBar?.progress = state.percent
                progressPercent?.text = scene.getString(R.string.lrr_upload_percent, state.percent)
            }

            is GalleryListViewModel.UploadUiState.DuplicateSkipped -> {
                dismiss()
                scene.showTip(R.string.lrr_upload_duplicate, BaseScene.LENGTH_LONG)
                viewModel.resetUploadState()
            }

            is GalleryListViewModel.UploadUiState.Success -> {
                dismiss()
                scene.showTip(R.string.lrr_upload_success, BaseScene.LENGTH_LONG)
                scene.mHelper?.refresh()
                viewModel.resetUploadState()
            }

            is GalleryListViewModel.UploadUiState.Failed -> {
                dismiss()
                val error = state.error
                if (error is java.net.SocketException || error is javax.net.ssl.SSLException) {
                    // Connection torn down mid-transfer (broken pipe / connection
                    // reset / TLS record error). Usually a server or reverse-proxy
                    // upload-size limit or an unstable link — a bare "Broken pipe"
                    // tells the user nothing actionable, so point at the likely cause.
                    scene.showTip(R.string.lrr_upload_failed_connection, BaseScene.LENGTH_LONG)
                } else {
                    val context = scene.ehContext
                    val message = if (context != null) {
                        friendlyError(context, error)
                    } else {
                        error.message.orEmpty()
                    }
                    scene.showTip(scene.getString(R.string.lrr_upload_failed, message), BaseScene.LENGTH_LONG)
                }
                viewModel.resetUploadState()
            }
        }
    }

    private fun show() {
        if (dialog?.isShowing == true) return
        val activity = scene.activity2 ?: return
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_upload_progress, null, false)
        progressTitle = view.findViewById(R.id.upload_progress_title)
        progressBar = view.findViewById(R.id.upload_progress_bar)
        progressPercent = view.findViewById(R.id.upload_progress_percent)
        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton(R.string.cancel) { d, _ -> d.cancel() }
            .setCancelable(true)
            .create()
            .also { dialog ->
                dialog.setOnCancelListener { scene.viewModel.cancelUpload() }
                dialog.show()
            }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        progressBar = null
        progressTitle = null
        progressPercent = null
    }
}
