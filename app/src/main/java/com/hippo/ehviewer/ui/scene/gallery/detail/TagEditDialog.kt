package com.hippo.ehviewer.ui.scene.gallery.detail

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.hippo.ehviewer.client.lrr.LRRArchiveApi
import com.hippo.ehviewer.client.lrr.LRRClientProvider
import com.hippo.ehviewer.client.lrr.friendlyError
import com.hippo.ehviewer.client.lrr.runSuspend
import com.hippo.util.IoThreadPoolExecutor

/**
 * Dialog for editing archive tags. Shows the current tags in a multiline EditText
 * and syncs changes to the LANraragi server via [LRRArchiveApi.updateMetadata].
 */
object TagEditDialog {

    fun interface Callback {
        fun onTagsUpdated()
    }

    /**
     * Reconstruct the raw LANraragi-format tag string from [GalleryTagGroup] array.
     * Format: "namespace:tag1, namespace:tag2, ..."
     */
    @JvmStatic
    fun tagsToString(tagGroups: Array<GalleryTagGroup>?): String {
        if (tagGroups.isNullOrEmpty()) return ""
        return buildList {
            for (group in tagGroups) {
                val namespace = group.groupName ?: "misc"
                for (i in 0 until group.size()) {
                    add("$namespace:${group.getTagAt(i)}")
                }
            }
        }.joinToString(", ")
    }

    /**
     * Show the tag edit dialog.
     *
     * @param activity  current activity
     * @param arcid     LANraragi archive ID (GalleryInfo.token)
     * @param tagGroups current tag groups from GalleryDetail
     * @param callback  called on the UI thread after a successful update
     */
    @JvmStatic
    fun show(
        activity: Activity?,
        arcid: String?,
        tagGroups: Array<GalleryTagGroup>?,
        callback: Callback?
    ) {
        if (activity == null || arcid.isNullOrEmpty()) return

        val currentTags = tagsToString(tagGroups)

        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val editText = EditText(activity).apply {
            setText(currentTags)
            hint = "namespace:tag1, namespace:tag2, ..."
            minLines = 3
            isSingleLine = false
            setPadding(padding, padding, padding, padding)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_edit_tags)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newTags = editText.text.toString().trim()
                performUpdate(activity, arcid, newTags, callback)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performUpdate(
        activity: Activity,
        arcid: String,
        tags: String,
        callback: Callback?
    ) {
        IoThreadPoolExecutor.instance.execute {
            try {
                runSuspend {
                    LRRArchiveApi.updateMetadata(
                        LRRClientProvider.getClient(),
                        LRRClientProvider.getBaseUrl(),
                        arcid,
                        tags = tags
                    )
                }
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.lrr_tags_updated, Toast.LENGTH_SHORT).show()
                    callback?.onTagsUpdated()
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        friendlyError(activity, e),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
