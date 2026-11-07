package com.lanraragi.reader.ui.scene.gallery.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.framework.util.attrColor
import com.lanraragi.reader.R
import com.lanraragi.reader.domain.BARE_TAG_BUCKET
import com.lanraragi.reader.domain.MetadataPlugin
import com.lanraragi.reader.domain.splitNamespace
import com.lanraragi.reader.tagtranslation.TagTranslationDatabase

/** One row of the plugin screen: a plugin to choose, or part of the review. */
sealed interface PluginTagRow {
    data class Plugin(val plugin: MetadataPlugin) : PluginTagRow
    data class Header(val namespace: String) : PluginTagRow
    data class Tag(
        val tag: String,
        val namespace: String,
        val value: String,
        val checked: Boolean,
        val enabled: Boolean,
    ) : PluginTagRow
    data class Title(val text: ReviewText, val enabled: Boolean) : PluginTagRow
    data class Summary(val text: ReviewText, val enabled: Boolean) : PluginTagRow
}

/**
 * The review as rows: title and summary first, then the tags grouped by
 * namespace. Rows are disabled during the write: a CheckBox flips itself before
 * the view model answers, and it ignores changes then, so the box would show
 * something other than what a retry writes.
 */
internal fun pluginTagReviewRows(review: PluginTagUiState.Review): List<PluginTagRow> = buildList {
    val enabled = !review.applying
    review.title?.let { add(PluginTagRow.Title(it, enabled)) }
    review.summary?.let { add(PluginTagRow.Summary(it, enabled)) }
    review.tags
        .map { it to splitNamespace(it.tag) }
        .groupBy { (_, parts) -> if (parts.size == 2) parts[0] else BARE_TAG_BUCKET }
        .forEach { (namespace, tags) ->
            add(PluginTagRow.Header(namespace))
            for ((tag, parts) in tags) add(PluginTagRow.Tag(tag.tag, namespace, parts.last(), tag.checked, enabled))
        }
}

internal class PluginTagAdapter(
    private val translations: TagTranslationDatabase?,
    private val onPlugin: (MetadataPlugin) -> Unit,
    private val onTag: (String, Boolean) -> Unit,
    private val onTitle: (Boolean) -> Unit,
    private val onSummary: (Boolean) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<PluginTagRow> = emptyList()

    fun submitList(new: List<PluginTagRow>) {
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = sameRow(old[o], new[n])
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
        })
        rows = new
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = rows.size

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is PluginTagRow.Plugin -> TYPE_PLUGIN
        is PluginTagRow.Header -> TYPE_HEADER
        else -> TYPE_CHECK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val margin = context.resources.getDimensionPixelSize(R.dimen.keyline_margin)
        val view = when (viewType) {
            TYPE_PLUGIN -> LayoutInflater.from(context).inflate(R.layout.item_plugin, parent, false)
            TYPE_HEADER -> TextView(context).apply {
                setTextColor(context.attrColor(R.attr.textColorThemeAccent))
                setPadding(margin, margin / 2, margin, 0)
            }
            else -> AppCompatCheckBox(context).apply { setPadding(margin / 2, 0, margin, 0) }
        }
        if (view.layoutParams == null) {
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val view = holder.itemView
        when (val row = rows[position]) {
            is PluginTagRow.Plugin -> bindPlugin(view, row.plugin)
            is PluginTagRow.Header ->
                (view as TextView).text = translations?.translateNamespace(row.namespace) ?: row.namespace
            is PluginTagRow.Tag -> {
                val translated = translations?.translateTag(row.namespace, row.value)
                bindCheck(view as CheckBox, translated?.let { "$it (${row.value})" } ?: row.value, row.checked, row.enabled) {
                    onTag(row.tag, !row.checked)
                }
            }
            is PluginTagRow.Title -> bindCheck(
                view as CheckBox, changeText(view, R.string.plugin_tags_title_row, row.text), row.text.checked, row.enabled,
            ) { onTitle(!row.text.checked) }
            is PluginTagRow.Summary -> bindCheck(
                view as CheckBox, changeText(view, R.string.tank_summary_hint, row.text), row.text.checked, row.enabled,
            ) { onSummary(!row.text.checked) }
        }
    }

    private fun bindPlugin(view: View, plugin: MetadataPlugin) {
        view.findViewById<TextView>(R.id.plugin_name).text =
            listOf(plugin.name, plugin.version).filter { it.isNotBlank() }.joinToString(" ")
        val description = view.findViewById<TextView>(R.id.plugin_description)
        val text = PluginTagScene.plainText(plugin.description)
        description.text = text
        description.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.plugin_login_hint).visibility =
            if (plugin.loginFrom != null) View.VISIBLE else View.GONE
        view.setOnClickListener { onPlugin(plugin) }
    }

    /** The checkbox only shows the state; a tap asks the view model, whose state comes back here. */
    private fun bindCheck(box: CheckBox, text: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
        box.text = text
        box.isChecked = checked
        box.isEnabled = enabled
        box.setOnClickListener { onClick() }
    }

    private fun changeText(view: View, labelRes: Int, change: ReviewText): String {
        val context = view.context
        val old = change.old.ifEmpty { context.getString(R.string.error_empty) }
        return "${context.getString(labelRes)}:\n$old \u2192 ${change.new}"
    }

    private companion object {
        const val TYPE_PLUGIN = 0
        const val TYPE_HEADER = 1
        const val TYPE_CHECK = 2
    }

    private fun sameRow(old: PluginTagRow, new: PluginTagRow): Boolean = when {
        old is PluginTagRow.Tag && new is PluginTagRow.Tag -> old.tag == new.tag
        old is PluginTagRow.Title && new is PluginTagRow.Title -> true
        old is PluginTagRow.Summary && new is PluginTagRow.Summary -> true
        else -> old == new
    }
}
