/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.ui.scene.gallery.detail

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.graphics.drawable.TransitionDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.R
import com.lanraragi.reader.client.api.PageThumbnailCache
import java.util.Locale

/**
 * RecyclerView Adapter for the detail-page thumbnail grid.
 *
 * **Data sources:**
 *  - **Layout** — driven by [submitPageCount]: a single number, the
 *    archive's total page count. Each grid cell is just a position
 *    index `0 .. pageCount - 1`.
 *  - **Status** — driven by [submitStates]: a `Map<page, PageState>`
 *    reflecting per-page request lifecycle.
 *  - **Bitmaps** — read on every bind from [PageThumbnailCache] using
 *    the current [arcid]. Keeping bitmaps off the Adapter (and off
 *    the VM map) is what lets the LRU cache actually evict — see
 *    [PageThumbnailsViewModel] kdoc.
 *
 * **No `notifyDataSetChanged`** (CLAUDE.md hard rule). Inserts use
 * `notifyItemRange*`; per-cell status changes use
 * `notifyItemChanged(pos, PAYLOAD_STATE)` and a payloads-aware
 * `onBindViewHolder` so only the ImageView is touched on partial
 * rebinds — no flicker on the page-number caption.
 *
 * **Fade-in on first arrival.** When a bitmap lands in the cache for
 * a cell that previously showed nothing (or a failure icon), the
 * image is crossfaded in over 300 ms via [TransitionDrawable] for a
 * gallery-style "tiles pop in" feel. When the recycler rebinds a cell
 * that already shows a bitmap (typical during scroll), the swap is
 * instant — fading every tile during a fast scroll would look like
 * the whole grid is flashing. Per-VH [VH.imageBindState] tracks what
 * was last set so the decision survives recycler churn.
 */
internal class PageThumbnailAdapter(
    private val onPageClick: (page: Int) -> Unit,
    private val onPageRetry: (page: Int) -> Unit,
) : RecyclerView.Adapter<PageThumbnailAdapter.VH>() {

    private var arcid: String? = null
    private var pageCount: Int = 0
    private var states: Map<Int, PageThumbnailsViewModel.PageState> = emptyMap()

    /**
     * Set the archive id used for cache lookups. Mass-rebinds every
     * cell so the new archive's bitmaps appear (or placeholders for
     * cells that have not been fetched yet).
     */
    fun submitArcid(newArcid: String?) {
        if (arcid == newArcid) return
        arcid = newArcid
        if (pageCount > 0) {
            notifyItemRangeChanged(0, pageCount, PAYLOAD_STATE)
        }
    }

    /**
     * Set the archive's page count. Drives item-range insert/remove —
     * never a `notifyDataSetChanged`. No-ops on unchanged values so
     * back→forward navigations to the same archive do not flash the
     * grid.
     */
    fun submitPageCount(newCount: Int) {
        val old = pageCount
        if (old == newCount) return
        pageCount = newCount
        when {
            old == 0 && newCount > 0 -> notifyItemRangeInserted(0, newCount)
            newCount > old -> notifyItemRangeInserted(old, newCount - old)
            newCount < old -> notifyItemRangeRemoved(newCount, old - newCount)
        }
    }

    /**
     * Apply a new per-page state snapshot. Diffs against the previous
     * snapshot and only fires `notifyItemChanged` for cells that
     * actually changed status, so the user does not see needless
     * flicker on every emission.
     */
    fun submitStates(newStates: Map<Int, PageThumbnailsViewModel.PageState>) {
        val old = states
        states = newStates
        if (pageCount == 0) return
        // Union of both maps' keyspaces — any disappearing entry is
        // also a state change (typically Loading → Ready, i.e. removed).
        val touched = HashSet<Int>(old.size + newStates.size)
        touched.addAll(old.keys)
        touched.addAll(newStates.keys)
        for (page in touched) {
            if (page in 0 until pageCount && old[page] != newStates[page]) {
                notifyItemChanged(page, PAYLOAD_STATE)
            }
        }
    }

    /**
     * Mark [page] as needing an image refresh. Used by the Scene
     * when a bitmap lands in the cache but the state map already
     * dropped the Loading entry (status change → cache write race).
     */
    fun refreshPage(page: Int) {
        if (page in 0 until pageCount) {
            notifyItemChanged(page, PAYLOAD_STATE)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_page_thumbnail, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = pageCount

    override fun onBindViewHolder(holder: VH, position: Int) {
        bindFull(holder, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) bindFull(holder, position) else bindImage(holder, position)
    }

    private fun bindFull(holder: VH, position: Int) {
        // Page numbers are 1-indexed for users. Format with the
        // system locale so RTL / non-Arabic-numeral locales render
        // correctly (lint SetTextI18n).
        holder.pageNumber.text = String.format(Locale.getDefault(), "%d", position + 1)
        holder.itemView.setOnClickListener {
            val state = states[position]
            if (state is PageThumbnailsViewModel.PageState.Failed) {
                onPageRetry(position)
            } else {
                onPageClick(position)
            }
        }
        bindImage(holder, position)
    }

    private fun bindImage(holder: VH, position: Int) {
        val currentArcid = arcid
        if (currentArcid == null) {
            holder.image.setImageDrawable(null)
            holder.imageBindState = ImageBindState.EMPTY
            return
        }
        val cachedBitmap = PageThumbnailCache.get(currentArcid, position)
        when {
            cachedBitmap != null -> {
                if (holder.imageBindState == ImageBindState.BITMAP) {
                    // Fast swap during recycler churn (scroll): a cell
                    // that already shows another archive's bitmap is
                    // being rebound to its new page. Fading every tile
                    // here would look like the whole grid is flashing.
                    holder.image.setImageBitmap(cachedBitmap)
                } else {
                    // Empty → bitmap (first arrival after a cache miss
                    // resolved) or Failed → bitmap (recovery after the
                    // user tapped a broken tile and the retry landed).
                    // 300 ms crossfade softens the transition so the
                    // grid feels like it is breathing rather than
                    // hard-cutting.
                    fadeInBitmap(holder.image, cachedBitmap)
                }
                holder.imageBindState = ImageBindState.BITMAP
            }
            states[position] is PageThumbnailsViewModel.PageState.Failed -> {
                holder.image.setImageResource(R.drawable.image_failed_new)
                holder.imageBindState = ImageBindState.FAILED
            }
            else -> {
                holder.image.setImageDrawable(null)
                holder.imageBindState = ImageBindState.EMPTY
            }
        }
    }

    private fun fadeInBitmap(imageView: ImageView, bitmap: Bitmap) {
        val from = Color.TRANSPARENT.toDrawable()
        val to = bitmap.toDrawable(imageView.resources)
        // LayerDrawable.getIntrinsicWidth/Height returns the max of
        // children — ColorDrawable reports -1, so the transition's
        // intrinsic size equals the bitmap's. That keeps the smart
        // CENTER_CROP/FIT_CENTER decision in AspectClampedImageView
        // working on the transitioning drawable too.
        val transition = TransitionDrawable(arrayOf(from, to))
        transition.isCrossFadeEnabled = true
        imageView.setImageDrawable(transition)
        transition.startTransition(FADE_DURATION_MS)
    }

    internal enum class ImageBindState { EMPTY, FAILED, BITMAP }

    internal class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.page_thumb_image)
        val pageNumber: TextView = view.findViewById(R.id.page_thumb_number)

        /**
         * Last drawable kind set on [image]. Used by [bindImage] to
         * decide whether to crossfade (transition from empty/failed
         * to a bitmap) or do an instant swap (one bitmap → another,
         * typical during scroll rebind).
         */
        var imageBindState: ImageBindState = ImageBindState.EMPTY
    }

    private companion object {
        const val PAYLOAD_STATE: String = "page_state"
        const val FADE_DURATION_MS: Int = 300
    }
}
