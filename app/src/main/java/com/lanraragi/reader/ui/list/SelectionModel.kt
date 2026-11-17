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

import androidx.lifecycle.SavedStateHandle

/** A list screen's multi-select, as one value. Rows are named by a stable key (an arcid), not a position. */
sealed interface SelectionState {
    /** Not selecting. */
    data object None : SelectionState

    /**
     * Multi-select is on; [checked] are the selected rows, in the order they
     * were picked. Empty only when the mode was entered with nothing picked
     * ([SelectionModel.enter]).
     */
    data class Selecting(val checked: Set<String>) : SelectionState
}

/**
 * The owner of a list screen's [SelectionState]: the list view only renders
 * it and reports taps. Keyed by row key, so a list that changes under the
 * selection (a reload, a Room emission) cannot move a check onto another row;
 * keys the list no longer holds go with [retainAll].
 *
 * Kept in the screen's [SavedStateHandle] under [key] when one is given (up
 * to [MAX_SAVED] keys), so a rotation or a process-death restore comes back
 * selecting the same rows.
 * Unchecking the last row leaves the mode.
 */
class SelectionModel(
    private val saved: SavedStateHandle? = null,
    private val key: String = KEY,
) {
    /** Called after every change of [state]. */
    var onChanged: ((SelectionState) -> Unit)? = null

    var state: SelectionState = restored()
        private set

    val isActive: Boolean get() = state is SelectionState.Selecting

    val checked: Set<String> get() = (state as? SelectionState.Selecting)?.checked.orEmpty()

    val count: Int get() = checked.size

    fun isSelected(rowKey: String): Boolean = rowKey in checked

    /** [rowKey] picked or dropped: entering the mode with it, or leaving it when it was the last one. */
    fun toggle(rowKey: String) = setSelected(rowKey, !isSelected(rowKey))

    fun setSelected(rowKey: String, selected: Boolean) {
        val now = checked
        if ((rowKey in now) == selected) return
        update(if (selected) now + rowKey else now - rowKey)
    }

    /** Turns the mode on with nothing picked yet (a screen with an explicit "select" button). */
    fun enter() {
        if (!isActive) update(emptySet(), stayOn = true)
    }

    /** Selects every key of [rowKeys] (select all); keeps the order of the earlier picks first. */
    fun selectAll(rowKeys: Collection<String>) {
        if (!isActive) return
        update(checked + rowKeys)
    }

    /** Drops the selected keys the list no longer holds (a reload or a removal). */
    fun retainAll(rowKeys: Collection<String>) {
        if (!isActive) return
        val present = rowKeys.toHashSet()
        val kept = checked.filterTo(LinkedHashSet()) { it in present }
        if (kept.size != count) update(kept)
    }

    /** Leaves the mode. */
    fun exit() = update(emptySet())

    private fun update(keys: Set<String>, stayOn: Boolean = false) {
        val next = if (keys.isEmpty() && !stayOn) SelectionState.None else SelectionState.Selecting(LinkedHashSet(keys))
        if (next == state) return
        state = next
        // A selection too large for the saved-state bundle is not kept (the
        // bundle would crash the stop with TransactionTooLargeException).
        saved?.set(key, (next as? SelectionState.Selecting)?.checked?.takeIf { it.size <= MAX_SAVED }?.let(::ArrayList))
        onChanged?.invoke(next)
    }

    private fun restored(): SelectionState {
        // null = not selecting; an empty list = on with nothing picked.
        val keys = saved?.get<ArrayList<String>>(key)
        return if (keys == null) SelectionState.None else SelectionState.Selecting(LinkedHashSet(keys))
    }

    companion object {
        /** The SavedStateHandle key of a screen with one selectable list. */
        const val KEY = "selection"

        /** The most keys saved with the screen (about 45 bytes each in the bundle). */
        const val MAX_SAVED = 2000
    }
}
