package com.lanraragi.reader.ui.scene

import androidx.recyclerview.widget.DiffUtil
import com.lanraragi.reader.domain.Category

/**
 * DiffUtil callback for Category lists. Identity is the category `id`
 * (LANraragi's CAT-prefixed string id). Content compares everything that
 * onBindViewHolder reads so the row repaints when name/pinned/search/
 * archive count change.
 */
internal class LRRCategoryDiffCallback(
    private val oldList: List<Category>,
    private val newList: List<Category>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size
    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val o = oldList[oldItemPosition]
        val n = newList[newItemPosition]
        return o.name == n.name &&
            o.isPinned() == n.isPinned() &&
            o.isDynamic() == n.isDynamic() &&
            o.search == n.search &&
            o.archives.size == n.archives.size
    }
}
