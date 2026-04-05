package com.hippo.ehviewer.ui.scene.gallery.detail

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.android.resource.AttrResources
import com.hippo.drawable.RoundSideRectDrawable
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.GalleryTagGroup
import com.hippo.ehviewer.client.lrr.LRRArchiveApi
import com.hippo.ehviewer.client.lrr.LRRClientProvider
import com.hippo.ehviewer.client.lrr.LRRTagCache
import com.hippo.ehviewer.client.lrr.friendlyError
import com.hippo.ehviewer.client.lrr.runSuspend
import com.hippo.util.IoThreadPoolExecutor
import com.hippo.widget.AutoWrapLayout

/**
 * Dialog for editing archive tags. Shows tags grouped by namespace using the
 * same visual style as the gallery detail page (RoundSideRectDrawable chips
 * in AutoWrapLayout). Click to edit, long-press to delete, [+] to add per
 * namespace, and a bottom button to add new namespaces.
 *
 * Syncs changes to the LANraragi server via [LRRArchiveApi.updateMetadata].
 */
object TagEditDialog {

    fun interface Callback {
        fun onTagsUpdated()
    }

    /**
     * Mutable model for a namespace + its tags, used during editing.
     */
    private data class EditableTagGroup(
        var namespace: String,
        val tags: MutableList<String>
    )

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
     * Reconstruct the tag string from editable model.
     */
    private fun editableGroupsToString(groups: List<EditableTagGroup>): String {
        return buildList {
            for (group in groups) {
                for (tag in group.tags) {
                    if (tag.isNotBlank()) {
                        add("${group.namespace}:$tag")
                    }
                }
            }
        }.joinToString(", ")
    }

    /**
     * Parse [GalleryTagGroup] array into mutable editing model.
     */
    private fun parseTagGroups(tagGroups: Array<GalleryTagGroup>?): MutableList<EditableTagGroup> {
        if (tagGroups.isNullOrEmpty()) return mutableListOf()
        return tagGroups.map { group ->
            EditableTagGroup(
                namespace = group.groupName ?: "misc",
                tags = (0 until group.size()).map { group.getTagAt(it) }.toMutableList()
            )
        }.toMutableList()
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

        val groups = parseTagGroups(tagGroups)
        val density = activity.resources.displayMetrics.density

        val colorTag = AttrResources.getAttrColor(activity, R.attr.tagBackgroundColor)
        val colorNamespace = AttrResources.getAttrColor(activity, R.attr.tagGroupBackgroundColor)

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // Rebuild the entire view tree from the current groups model
        fun rebuildViews() {
            rootLayout.removeAllViews()

            for ((groupIndex, group) in groups.withIndex()) {
                val rowLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val vPad = (4 * density).toInt()
                    setPadding(0, vPad, 0, vPad)
                }

                // Namespace label
                val nsLabel = createTagTextView(activity, density).apply {
                    text = group.namespace
                    background = RoundSideRectDrawable(colorNamespace)
                    setTypeface(null, Typeface.BOLD)
                    setOnClickListener {
                        showEditNamespaceDialog(activity, group) {
                            rebuildViews()
                        }
                    }
                    setOnLongClickListener {
                        groups.removeAt(groupIndex)
                        rebuildViews()
                        true
                    }
                }
                rowLayout.addView(nsLabel)

                // Tag flow layout
                val tagFlow = AutoWrapLayout(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                for ((tagIndex, tagText) in group.tags.withIndex()) {
                    val tagView = createTagTextView(activity, density).apply {
                        text = "$tagText \u2715"
                        background = RoundSideRectDrawable(colorTag)
                        setOnClickListener {
                            showEditTagDialog(activity, tagText) { newTag ->
                                if (newTag.isNotBlank()) {
                                    group.tags[tagIndex] = newTag
                                    rebuildViews()
                                }
                            }
                        }
                        setOnLongClickListener {
                            group.tags.removeAt(tagIndex)
                            rebuildViews()
                            true
                        }
                    }
                    tagFlow.addView(tagView)
                }

                // [+] add tag button
                val addBtn = createAddButton(activity, density, colorTag)
                addBtn.setOnClickListener {
                    showAddTagDialog(activity, group.namespace) { newTag ->
                        if (newTag.isNotBlank()) {
                            group.tags.add(newTag)
                            rebuildViews()
                        }
                    }
                }
                tagFlow.addView(addBtn)

                rowLayout.addView(tagFlow)
                rootLayout.addView(rowLayout)
            }

            // [+ Add Namespace] button at bottom
            val addNsBtn = TextView(activity).apply {
                text = activity.getString(R.string.lrr_add_namespace)
                val hPad = (16 * density).toInt()
                val vPad = (8 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                background = RoundSideRectDrawable(colorNamespace)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (8 * density).toInt()
                layoutParams = lp
                setOnClickListener {
                    showAddNamespaceDialog(activity) { ns ->
                        if (ns.isNotBlank()) {
                            groups.add(EditableTagGroup(ns, mutableListOf()))
                            rebuildViews()
                        }
                    }
                }
            }
            rootLayout.addView(addNsBtn)
        }

        rebuildViews()

        val scrollView = ScrollView(activity).apply {
            addView(rootLayout)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_edit_tags)
            .setView(scrollView)
            .setPositiveButton(R.string.lrr_save) { _, _ ->
                val newTags = editableGroupsToString(groups)
                performUpdate(activity, arcid, newTags, callback)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Create a tag-styled [TextView] matching item_gallery_tag.xml appearance.
     * CardMessage style: 14sp, singleLine, ellipsize end, white text.
     */
    private fun createTagTextView(activity: Activity, density: Float): TextView {
        return TextView(activity).apply {
            val hPad = (12 * density).toInt()
            val vPad = (4 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            val margin = (4 * density).toInt()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(margin, margin, margin, margin)
            }
        }
    }

    /**
     * Create a small [+] button styled as a tag chip.
     */
    private fun createAddButton(activity: Activity, density: Float, color: Int): TextView {
        return createTagTextView(activity, density).apply {
            text = "+"
            setTypeface(null, Typeface.BOLD)
            background = RoundSideRectDrawable(color)
            gravity = Gravity.CENTER
        }
    }

    /**
     * Dialog to edit an existing tag's text.
     */
    private fun showEditTagDialog(
        activity: Activity,
        currentTag: String,
        onResult: (String) -> Unit
    ) {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val editText = EditText(activity).apply {
            setText(currentTag)
            hint = activity.getString(R.string.lrr_tag_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_edit_tag)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onResult(editText.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Dialog to edit a namespace label.
     */
    private fun showEditNamespaceDialog(
        activity: Activity,
        group: EditableTagGroup,
        onResult: () -> Unit
    ) {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val editText = EditText(activity).apply {
            setText(group.namespace)
            hint = activity.getString(R.string.lrr_namespace_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_edit_tag)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newNs = editText.text.toString().trim()
                if (newNs.isNotBlank()) {
                    group.namespace = newNs
                    onResult()
                } else {
                    Toast.makeText(activity, R.string.lrr_empty_namespace, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Dialog to add a new tag with autocomplete from [LRRTagCache].
     */
    private fun showAddTagDialog(
        activity: Activity,
        namespace: String,
        onResult: (String) -> Unit
    ) {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val autoComplete = AutoCompleteTextView(activity).apply {
            hint = activity.getString(R.string.lrr_tag_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            threshold = 1
        }
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(autoComplete)
        }

        // Set up autocomplete adapter that queries LRRTagCache
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_dropdown_item_1line)
        autoComplete.setAdapter(adapter)
        autoComplete.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val keyword = s?.toString()?.trim() ?: return
                if (keyword.isBlank()) return
                val suggestions = LRRTagCache.suggest(keyword)
                    .filter { it.namespace.equals(namespace, ignoreCase = true) }
                    .map { it.text }
                adapter.clear()
                adapter.addAll(suggestions)
                adapter.notifyDataSetChanged()
            }
        })

        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_add_tag)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onResult(autoComplete.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Dialog to add a new namespace group.
     */
    private fun showAddNamespaceDialog(
        activity: Activity,
        onResult: (String) -> Unit
    ) {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val editText = EditText(activity).apply {
            hint = activity.getString(R.string.lrr_namespace_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(editText)
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.lrr_add_namespace)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ns = editText.text.toString().trim()
                if (ns.isNotBlank()) {
                    onResult(ns)
                } else {
                    Toast.makeText(activity, R.string.lrr_empty_namespace, Toast.LENGTH_SHORT).show()
                }
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
