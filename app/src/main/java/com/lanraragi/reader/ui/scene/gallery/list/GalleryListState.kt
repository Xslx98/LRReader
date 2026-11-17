/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.ui.scene.gallery.list

import androidx.annotation.StringRes
import com.lanraragi.reader.R
import com.lanraragi.reader.domain.Archive

/**
 * The rows the gallery list has loaded and where its pages end.
 *
 * @property rows the loaded rows, in list order.
 * @property pageEnds the row count after each loaded page (cumulative): page
 *   `p` covers `rows[pageStart(p) until pageEnds[p]]`. Pages load in order
 *   from 0, so `pageEnds.size` pages are loaded.
 * @property pageCount the pages the server has, as far as known.
 * @property emptyText what an empty result says (archives vs tankoubons).
 */
data class GalleryPages(
    val rows: List<Archive> = emptyList(),
    val pageEnds: List<Int> = emptyList(),
    val pageCount: Int = 0,
    @StringRes val emptyText: Int = R.string.gallery_list_empty_hit,
) {
    /** Pages loaded so far. */
    val loadedPages: Int get() = pageEnds.size

    /** Another page follows the loaded ones. */
    val canLoadMore: Boolean get() = loadedPages < pageCount

    /** First row of loaded page [page]. */
    fun pageStart(page: Int): Int = if (page == 0) 0 else pageEnds[page - 1]

    /** The first page of a (re)load. */
    fun first(rows: List<Archive>, pageCount: Int, @StringRes emptyText: Int): GalleryPages =
        GalleryPages(rows, listOf(rows.size), pageCount, emptyText)

    /** The next page appended; rows already among the last [DUPLICATE_RANGE] are dropped. */
    fun append(page: List<Archive>, pageCount: Int): GalleryPages {
        val fresh = withoutDuplicates(page, rows.size - DUPLICATE_RANGE, rows.size)
        return copy(
            rows = rows + fresh,
            pageEnds = pageEnds + (rows.size + fresh.size),
            pageCount = maxOf(loadedPages + 1, pageCount),
        )
    }

    /**
     * The last loaded page loaded again (pull up at the end): its window is
     * replaced; rows within [DUPLICATE_RANGE] of it that the earlier pages
     * already hold are dropped.
     */
    fun replaceLastPage(page: List<Archive>, pageCount: Int): GalleryPages {
        if (loadedPages == 0) return first(page, pageCount, emptyText)
        val start = pageStart(loadedPages - 1)
        val kept = rows.subList(0, start)
        val fresh = page.filterNot { row ->
            (maxOf(0, start - DUPLICATE_RANGE) until start).any { kept[it].arcid == row.arcid }
        }
        return copy(
            rows = kept + fresh,
            pageEnds = pageEnds.dropLast(1) + (start + fresh.size),
            pageCount = maxOf(loadedPages, pageCount),
        )
    }

    /** Row [index] removed (a deletion, a merge); later page ends move up. */
    fun removeAt(index: Int): GalleryPages {
        if (index !in rows.indices) return this
        return copy(
            rows = rows.toMutableList().apply { removeAt(index) },
            pageEnds = pageEnds.map { if (index < it) it - 1 else it },
        )
    }

    /** [row] inserted at [index] (a merge's provisional tank); later page ends move down. */
    fun insertAt(index: Int, row: Archive): GalleryPages {
        val at = index.coerceIn(0, rows.size)
        return copy(
            rows = rows.toMutableList().apply { add(at, row) },
            pageEnds = pageEnds.map { if (at < it) it + 1 else it }.ifEmpty { listOf(1) },
        )
    }

    /** Row [index] replaced by [row] (a rating, a cover, a merge's provisional tank). */
    fun replaceAt(index: Int, row: Archive): GalleryPages {
        if (index !in rows.indices) return this
        return copy(rows = rows.toMutableList().apply { set(index, row) })
    }

    private fun withoutDuplicates(page: List<Archive>, from: Int, to: Int): List<Archive> {
        val recent = (maxOf(0, from) until minOf(rows.size, to)).mapTo(HashSet()) { rows[it].arcid }
        return page.filterNot { it.arcid in recent }
    }

    companion object {
        /** How far back an appended page is checked for rows the list already shows. */
        const val DUPLICATE_RANGE = 50
    }
}

/** What started a list load, which decides what the screen shows meanwhile. */
enum class GalleryLoadKind {
    /** The first load of the screen: the skeleton, without animation. */
    Initial,

    /** A new query or an explicit refresh: the skeleton, the rows hidden. */
    Refresh,

    /** Pull to refresh: the rows stay, the header spins. */
    Pull,

    /** The next page: the rows stay, the footer spins. */
    NextPage,

    /** Pull up at the end: the last page loads again, the footer spins. */
    LastPage;

    /** The rows stay on screen while this load runs (a failure is then only reported). */
    val keepsRows: Boolean get() = this == Pull || this == NextPage || this == LastPage
}

/**
 * The gallery list screen as one value, owned by [GalleryListViewModel]. The
 * scene renders it; every change goes through the steps below.
 */
sealed interface GalleryListState {

    /** What is loaded (kept while a load runs and after a failure). */
    val pages: GalleryPages

    /** Nothing asked yet: a new screen, or one restored after process death. */
    data object Idle : GalleryListState {
        override val pages: GalleryPages get() = GalleryPages()
    }

    /** Load [task] of [kind] runs; [pages] is what was loaded before it. */
    data class Loading(val kind: GalleryLoadKind, val task: Int, override val pages: GalleryPages) : GalleryListState

    /** [pages] are shown. */
    data class Content(override val pages: GalleryPages) : GalleryListState

    /** The query answered nothing; [pages] carries the wording. */
    data class Empty(override val pages: GalleryPages) : GalleryListState

    /** A load that hid the rows failed with [message]; retry loads again. */
    data class Error(val message: String, override val pages: GalleryPages) : GalleryListState

    /** The load whose result is awaited, or null. */
    val awaitedTask: Int? get() = (this as? Loading)?.task

    companion object {
        /** Load [task] of [kind] starts. */
        fun start(state: GalleryListState, kind: GalleryLoadKind, task: Int): GalleryListState =
            Loading(kind, task, state.pages)

        /**
         * Page [page] of load [task] answered [rows]; another task's answer
         * changes nothing (a newer load superseded it).
         */
        fun pageLoaded(
            state: GalleryListState,
            task: Int,
            rows: List<Archive>,
            pageCount: Int,
            @StringRes emptyText: Int,
        ): GalleryListState {
            if (state !is Loading || state.task != task) return state
            val pages = when (state.kind) {
                GalleryLoadKind.NextPage -> state.pages.append(rows, pageCount)
                GalleryLoadKind.LastPage -> state.pages.replaceLastPage(rows, pageCount)
                else -> state.pages.first(rows, pageCount, emptyText)
            }.copy(emptyText = emptyText)
            return settled(pages)
        }

        /**
         * Load [task] failed with [message]. A load that kept the rows on
         * screen goes back to them (the failure is only reported); one that
         * hid them is the error.
         */
        fun failed(state: GalleryListState, task: Int, message: String): GalleryListState {
            if (state !is Loading || state.task != task) return state
            return if (state.kind.keepsRows && state.pages.rows.isNotEmpty()) {
                Content(state.pages)
            } else {
                Error(message, state.pages)
            }
        }

        /** The rows were edited in place ([edit]); a list edited down to nothing is empty. */
        fun edited(state: GalleryListState, edit: (GalleryPages) -> GalleryPages): GalleryListState {
            val pages = edit(state.pages)
            return when (state) {
                is Loading -> state.copy(pages = pages)
                is Error -> state.copy(pages = pages)
                Idle -> state
                else -> settled(pages)
            }
        }

        private fun settled(pages: GalleryPages): GalleryListState =
            if (pages.rows.isEmpty()) Empty(pages) else Content(pages)
    }
}
