package com.lanraragi.reader.ui.scene.tankdetail

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.TransitionDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.PageThumbnailCache
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.tankoubon.TankPageGridLayout
import com.lanraragi.reader.tankoubon.TankPageGridLayout.Item
import com.lanraragi.reader.ui.scene.gallery.detail.PageThumbnailsViewModel.PageState
import java.util.Locale

/**
 * Continuous global-page grid of the tank detail page (spec 2026-09-22
 * §4.7): page cells (the archive page's `item_page_thumbnail`, numbered
 * by GLOBAL page) interleaved with full-span member dividers. Bitmaps
 * come from [PageThumbnailCache] under the member's own key; request
 * states are keyed by global page.
 */
internal class TankPageGridAdapter(
    private val onPageClick: (global0: Int) -> Unit,
    private val onPageRetry: (global0: Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var layout: TankPageGridLayout.Layout = TankPageGridLayout.build(emptyList())
    private var members: List<Archive> = emptyList()
    private var states: Map<Int, PageState> = emptyMap()

    /** Global page at adapter [position], or null for a divider. */
    fun globalAt(position: Int): Int? = layout.globalAt(position)

    fun positionOfGlobal(global0: Int): Int = layout.positionOfGlobal(global0)

    fun spanSize(position: Int, spanCount: Int): Int = layout.spanSize(position, spanCount)

    /** Replaces the whole grid (members changed = a different tank shape). */
    fun submit(newLayout: TankPageGridLayout.Layout, newMembers: List<Archive>) {
        layout = newLayout
        members = newMembers
        // Membership changes reshape every position; a full rebind is the honest diff.
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    /** Diffs per-page states and rebinds only the cells whose status changed. */
    fun submitStates(newStates: Map<Int, PageState>) {
        val old = states
        states = newStates
        val touched = HashSet<Int>(old.size + newStates.size)
        touched.addAll(old.keys)
        touched.addAll(newStates.keys)
        for (global0 in touched) {
            if (old[global0] != newStates[global0]) {
                val position = layout.positionOfGlobal(global0)
                if (position >= 0) notifyItemChanged(position, PAYLOAD_STATE)
            }
        }
    }

    override fun getItemCount(): Int = layout.size

    override fun getItemViewType(position: Int): Int =
        if (layout.itemAt(position) is Item.Divider) TYPE_DIVIDER else TYPE_PAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DIVIDER) {
            DividerHolder(inflater.inflate(R.layout.item_tank_page_divider, parent, false))
        } else {
            PageHolder(inflater.inflate(R.layout.item_page_thumbnail, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = layout.itemAt(position)) {
            is Item.Divider -> bindDivider(holder as DividerHolder, item)
            is Item.Page -> bindPageFull(holder as PageHolder, item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = layout.itemAt(position)
        if (payloads.isEmpty() || item !is Item.Page || holder !is PageHolder) {
            onBindViewHolder(holder, position)
        } else {
            bindPageImage(holder, item)
        }
    }

    private fun bindDivider(holder: DividerHolder, item: Item.Divider) {
        val title = members.getOrNull(item.memberIndex)?.title.orEmpty()
        holder.title.text = holder.title.context.getString(R.string.tank_page_divider, item.memberIndex + 1, title)
    }

    private fun bindPageFull(holder: PageHolder, item: Item.Page) {
        holder.pageNumber.text = String.format(Locale.getDefault(), "%d", item.global0 + 1)
        holder.itemView.setOnClickListener {
            if (states[item.global0] is PageState.Failed) onPageRetry(item.global0) else onPageClick(item.global0)
        }
        bindPageImage(holder, item)
    }

    private fun bindPageImage(holder: PageHolder, item: Item.Page) {
        val arcid = members.getOrNull(item.memberIndex)?.arcid
        val cached = arcid?.let { PageThumbnailCache.get(it, item.page0) }
        when {
            cached != null -> {
                if (holder.hasBitmap) holder.image.setImageBitmap(cached) else fadeIn(holder.image, cached)
                holder.hasBitmap = true
            }
            states[item.global0] is PageState.Failed -> {
                holder.image.setImageResource(R.drawable.image_failed_new)
                holder.hasBitmap = false
            }
            else -> {
                holder.image.setImageDrawable(null)
                holder.hasBitmap = false
            }
        }
    }

    private fun fadeIn(imageView: ImageView, bitmap: Bitmap) {
        val transition = TransitionDrawable(
            arrayOf(Color.TRANSPARENT.toDrawable(), bitmap.toDrawable(imageView.resources))
        )
        transition.isCrossFadeEnabled = true
        imageView.setImageDrawable(transition)
        transition.startTransition(FADE_DURATION_MS)
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.page_thumb_image)
        val pageNumber: TextView = view.findViewById(R.id.page_thumb_number)
        var hasBitmap = false
    }

    private class DividerHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tank_page_divider_title)
    }

    private companion object {
        const val TYPE_PAGE = 0
        const val TYPE_DIVIDER = 1
        const val PAYLOAD_STATE = "page_state"
        const val FADE_DURATION_MS = 300
    }
}
