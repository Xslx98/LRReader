/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.ui.list

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * The items a RecyclerView adapter shows, and the one path that changes them
 * (docs/diffutil-root-cause-analysis.md, snapshot ownership). The adapter
 * reads [items]; a new list goes through [submit], which diffs it against
 * what the adapter last saw and dispatches the change synchronously. A move
 * the user made by dragging goes through [move], so the next [submit] of the
 * same order is a no-op instead of a bounce-back.
 *
 * Replaces the per-screen `mLastSnapshot` + mutable list copies.
 *
 * @param callback the screen's diff for an (old, new) pair of lists.
 */
class ListDiffBinder<T>(
    private val callback: (old: List<T>, new: List<T>) -> DiffUtil.Callback,
) {
    private val shown = ArrayList<T>()

    /**
     * The adapter the changes are dispatched to: set when the view is built,
     * null once it is destroyed. [items] stay as they are either way: a view
     * still animating out keeps binding them, and a new view's adapter shows
     * them from the start, so the next [submit] diffs against what it shows.
     */
    var adapter: RecyclerView.Adapter<*>? = null

    /** What the adapter shows; read it in getItemCount / onBindViewHolder. */
    val items: List<T> get() = shown

    /** Show [newList]: diff it against [items] and dispatch the change to [adapter]. */
    fun submit(newList: List<T>) {
        val target = adapter
        if (target == null) {
            shown.clear()
            shown.addAll(newList)
            return
        }
        // A copy: dispatchUpdatesTo below may still ask the callback about
        // the old items (getChangePayload) after the shown list changed.
        val result = DiffUtil.calculateDiff(callback(ArrayList(shown), newList))
        shown.clear()
        shown.addAll(newList)
        result.dispatchUpdatesTo(target)
    }

    /** The user dragged the item at [from] to [to]: move it in [items] and tell the adapter. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in shown.indices || to !in shown.indices) return
        shown.add(to, shown.removeAt(from))
        adapter?.notifyItemMoved(from, to)
    }
}
