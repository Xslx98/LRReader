/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.reading

/**
 * Where a reader session starts and whether the saved-position restore may
 * still move it, as one value ([GalleryProvider2.restore]). Every change is a
 * pure step below, applied with a compare-and-set, so the GL, IO and main
 * threads that report pages, input and the server's answer cannot interleave
 * half-written fields.
 *
 * @property startPage the reading position: the saved page at open, then the
 *   page on screen (and what is saved).
 * @property baseline the page the reader was built with, taken at [started].
 *   A page report other than it is a candidate for the user's navigation.
 * @property userNavigated the user moved off [baseline] (or opened on an
 *   explicit page): the async restore must not move them any more.
 * @property userInteracted a touch or key arrived since [started]. Only then
 *   can a page report be the user's: while the reader opens the layout can
 *   settle through other pages first (a device reported page 0 for a moment
 *   before the saved page), and taking that echo for navigation cancelled
 *   the restore and saved page 0 over the reading position.
 * @property override the explicit open page from the launch intent (a
 *   thumbnail-grid tap), or [NO_OVERRIDE] when the saved progress decides.
 */
data class RestoreState(
    val startPage: Int = 0,
    val baseline: Int = 0,
    val userNavigated: Boolean = false,
    val userInteracted: Boolean = false,
    val override: Int = NO_OVERRIDE,
) {

    /** The page the reader lands on first: the explicit open page, else the saved one. */
    val entryPage: Int get() = if (override >= 0) override else startPage

    /**
     * A session starts on [startPage]: that is the baseline, input from an
     * earlier session is forgotten, and an explicit open page other than the
     * saved one counts as the user's choice, so the restore must not move it.
     */
    fun started(): RestoreState = copy(
        baseline = startPage,
        userNavigated = override >= 0 && override != startPage,
        userInteracted = false,
    )

    /** The user touched the reader or pressed a key. */
    fun interacted(): RestoreState = copy(userInteracted = true)

    /** The reader shows [page]: it is the position now, and the user's navigation if they caused it. */
    fun pageShown(page: Int): RestoreState = copy(
        startPage = page,
        userNavigated = userNavigated || (page != baseline && userInteracted),
    )

    /**
     * The open-time reconcile (server or snapshot progress) moved the saved
     * position to [page]. Ignored once the user navigated: the page on
     * screen is the position then, and the restore will not jump.
     */
    fun reconciled(page: Int): RestoreState = if (userNavigated) this else copy(startPage = page)

    /**
     * The page the async restore jumps to for a resolved [page] and saved
     * scroll [fraction], or null: the user navigated, or nothing was saved
     * beyond the top of the first page.
     */
    fun resolve(page: Int, fraction: Float): Int? =
        if (!userNavigated && (page > 0 || fraction > 0f)) page else null

    companion object {
        const val NO_OVERRIDE = -1
    }
}
