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
import androidx.recyclerview.widget.ListUpdateCallback
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
 * @param fullReloadAbove past this many changed rows (on either side, after
 *   the common head) a change is dispatched as remove + insert of that window
 *   instead of a diff: DiffUtil runs on the main thread, and diffing a long
 *   scrolled list would make every reload cost a frame or more.
 */
class ListDiffBinder<T>(
    private val fullReloadAbove: Int = Int.MAX_VALUE,
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

    /**
     * Show [newList]: diff it against [items] and dispatch the change to
     * [adapter]. Rows equal at the head of both lists are skipped, so a page
     * appended at the end is a plain insert and a reloaded last page is
     * diffed alone, however far the list was scrolled.
     */
    fun submit(newList: List<T>) {
        val target = adapter
        if (target == null) {
            shown.clear()
            shown.addAll(newList)
            return
        }
        var head = 0
        val common = minOf(shown.size, newList.size)
        while (head < common && shown[head] == newList[head]) head++
        // Copies: dispatchUpdatesTo below may still ask the callback about the
        // old items (getChangePayload) after the shown list changed.
        val oldTail = ArrayList(shown.subList(head, shown.size))
        val newTail = ArrayList(newList.subList(head, newList.size))
        shown.clear()
        shown.addAll(newList)
        if (oldTail.size > fullReloadAbove || newTail.size > fullReloadAbove) {
            if (oldTail.isNotEmpty()) target.notifyItemRangeRemoved(head, oldTail.size)
            if (newTail.isNotEmpty()) target.notifyItemRangeInserted(head, newTail.size)
            return
        }
        DiffUtil.calculateDiff(callback(oldTail, newTail)).dispatchUpdatesTo(OffsetUpdates(target, head))
    }

    /** Dispatches a diff of a window starting at [offset] to the whole adapter. */
    private class OffsetUpdates(private val adapter: RecyclerView.Adapter<*>, private val offset: Int) :
        ListUpdateCallback {
        override fun onInserted(position: Int, count: Int) = adapter.notifyItemRangeInserted(position + offset, count)
        override fun onRemoved(position: Int, count: Int) = adapter.notifyItemRangeRemoved(position + offset, count)
        override fun onMoved(fromPosition: Int, toPosition: Int) =
            adapter.notifyItemMoved(fromPosition + offset, toPosition + offset)

        override fun onChanged(position: Int, count: Int, payload: Any?) =
            adapter.notifyItemRangeChanged(position + offset, count, payload)
    }

    /**
     * Shows [newList] with the screen's own notifications ([notify]) instead
     * of a diff: an edit whose animation the screen chooses (a row replaced
     * in place is a change, which a diff would call a remove + insert). The
     * next [submit] of the same list is then a no-op.
     */
    fun edit(newList: List<T>, notify: RecyclerView.Adapter<*>.() -> Unit) {
        shown.clear()
        shown.addAll(newList)
        adapter?.notify()
    }

    /** The user dragged the item at [from] to [to]: move it in [items] and tell the adapter. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in shown.indices || to !in shown.indices) return
        shown.add(to, shown.removeAt(from))
        adapter?.notifyItemMoved(from, to)
    }
}
