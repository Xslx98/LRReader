package com.hippo.ehviewer.ui.gallery

import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.widget.StampOverlayView
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRStampApi.StampData

/**
 * UI glue for stamps: placement mode, dialogs, marker tap card, error toasts.
 * Data lives in [ReaderStampsController]; this class never talks to the
 * network directly.
 */
class GalleryStampOps(
    private val activity: GalleryActivity,
    private val controller: ReaderStampsController,
    private val overlay: StampOverlayView,
) : StampOverlayView.Callback {

    private val placingBar: View = activity.findViewById(R.id.stamps_placing_bar)

    init {
        activity.findViewById<Button>(R.id.stamps_placing_cancel)
            .setOnClickListener { exitPlacementMode() }
    }

    fun isAvailable(): Boolean =
        controller.support != ReaderStampsController.Support.UNSUPPORTED

    fun startPlacementMode() {
        controller.sessionVisible = true
        activity.refreshStampsVisibility()
        controller.refreshIndex()
        overlay.placing = true
        placingBar.isVisible = true
    }

    fun exitPlacementMode() {
        overlay.placing = false
        placingBar.isVisible = false
    }

    override fun onPlaceRequested(page0: Int, normX: Float, normY: Float) {
        val edit = EditText(activity).apply {
            setHint(R.string.stamps_text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.stamps_add_title)
            .setView(edit)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = edit.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@setPositiveButton
                controller.addStamp(page0, text, normX, normY) { error ->
                    if (error != null) showError(error) else exitPlacementMode()
                }
            }
            .show()
    }

    override fun onStampTapped(stamp: StampData, page0: Int, screenX: Float, screenY: Float) {
        // View card lands in the next commit.
    }

    override fun onStampDropped(stamp: StampData, page0: Int, normX: Float, normY: Float) {
        // Drag reposition lands in a later commit.
        overlay.cancelDrag()
    }

    fun showError(e: Throwable) {
        val msg = if ((e as? LRRHttpException)?.code == LOCKED_HTTP_CODE) {
            activity.getString(R.string.stamps_locked)
        } else {
            val detail = (e as? LRRHttpException)?.serverError ?: e.message.orEmpty()
            activity.getString(R.string.stamps_op_failed, detail)
        }
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val LOCKED_HTTP_CODE = 423
    }
}
