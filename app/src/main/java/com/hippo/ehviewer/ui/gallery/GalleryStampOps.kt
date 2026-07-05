package com.hippo.ehviewer.ui.gallery

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.ehviewer.widget.StampOverlayView
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRStampApi.StampData
import kotlin.math.roundToInt

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

    private var card: PopupWindow? = null

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

    fun showStampedPagesDialog() {
        controller.sessionVisible = true
        activity.refreshStampsVisibility()
        val pages = controller.stampedPagesSorted()
        if (pages == null) {
            controller.refreshIndex { ok ->
                if (ok) {
                    showStampedPagesDialog()
                } else if (controller.support == ReaderStampsController.Support.UNSUPPORTED) {
                    Toast.makeText(activity, R.string.stamps_unsupported, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, R.string.stamps_list_error, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (pages.isEmpty()) {
            Toast.makeText(activity, R.string.stamps_list_empty, Toast.LENGTH_SHORT).show()
            return
        }

        fun label(page1: Int): String {
            val preview = controller.previewForPage(page1)
            return activity.getString(
                R.string.stamps_list_entry, page1, if (preview.isNullOrEmpty()) "…" else preview
            )
        }

        val adapter = ArrayAdapter(
            activity, android.R.layout.simple_list_item_1, pages.map(::label).toMutableList()
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.stamps_list_title)
            .setAdapter(adapter) { _, which -> activity.jumpToPage(pages[which] - 1) }
            .show()
        // Lazily fetch missing previews; refresh rows as they land. ListView adapter
        // in an AlertDialog — the RecyclerView notifyDataSetChanged red line doesn't apply.
        controller.ensurePagesLoaded(pages) {
            adapter.clear()
            adapter.addAll(pages.map(::label))
            adapter.notifyDataSetChanged()
        }
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
        card?.dismiss()
        // Inflate with the activity root as parent (never null: InflateParams lint).
        val root = activity.findViewById<ViewGroup>(R.id.main)
        val view = LayoutInflater.from(activity).inflate(R.layout.stamp_view_card, root, false)
        view.findViewById<TextView>(R.id.stamp_card_text).text = stamp.content

        val popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        // Background required so outside-touch dismiss works on all APIs.
        popup.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        view.findViewById<Button>(R.id.stamp_card_edit).setOnClickListener {
            popup.dismiss()
            showEditDialog(stamp, page0)
        }
        view.findViewById<Button>(R.id.stamp_card_delete).setOnClickListener {
            popup.dismiss()
            showDeleteConfirm(stamp, page0)
        }

        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val offsetPx = (CARD_OFFSET_DP * activity.resources.displayMetrics.density).roundToInt()
        val x = screenX.roundToInt()
            .coerceAtMost(overlay.width - view.measuredWidth)
            .coerceAtLeast(0)
        val y = (screenY.roundToInt() + offsetPx)
            .coerceAtMost(overlay.height - view.measuredHeight)
            .coerceAtLeast(0)
        // Coordinate-space invariant: (x, y) are overlay-local while
        // showAtLocation positions in window coordinates. The overlay fills
        // the immersive fullscreen root with no inset consumption between
        // the window origin and the overlay origin, so the two spaces
        // coincide here. Revisit if this Activity ever gains
        // fitsSystemWindows or a toolbar above stamp_overlay.
        popup.showAtLocation(overlay, Gravity.NO_GRAVITY, x, y)
        card = popup
    }

    private fun showEditDialog(stamp: StampData, page0: Int) {
        val edit = EditText(activity).apply {
            setText(stamp.content)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.stamps_edit_title)
            .setView(edit)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = edit.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@setPositiveButton
                controller.updateStamp(page0 + 1, stamp.id, content = text) { error ->
                    if (error != null) showError(error)
                }
            }
            .show()
    }

    private fun showDeleteConfirm(stamp: StampData, page0: Int) {
        AlertDialog.Builder(activity)
            .setMessage(R.string.stamps_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.stamps_delete) { _, _ ->
                controller.deleteStamp(page0 + 1, stamp.id) { error ->
                    if (error != null) showError(error)
                }
            }
            .show()
    }

    fun dismissCard() {
        card?.dismiss()
        card = null
    }

    override fun onStampDropped(stamp: StampData, page0: Int, normX: Float, normY: Float) {
        val position = StampGeometry.formatPosition(normX, normY)
        controller.updateStamp(page0 + 1, stamp.id, position = position) { error ->
            // Defensive: the view already resets its own drag state on ACTION_UP.
            // This covers a future view change where the drag visual might persist
            // until the update is confirmed.
            overlay.cancelDrag()
            if (error != null) showError(error)
        }
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
        private const val CARD_OFFSET_DP = 24f
    }
}
