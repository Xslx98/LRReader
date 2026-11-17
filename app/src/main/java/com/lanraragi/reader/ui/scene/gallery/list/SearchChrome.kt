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

/**
 * What the gallery list's search chrome shows, as one value owned by
 * [GalleryListViewModel] (saved with the screen). The search bar, the
 * advanced search layout, the FABs and the drawer locks are renderings of it
 * ([GalleryStateHelper.render]). The tag filter is independent of it.
 */
enum class SearchChrome {
    /** The list; the search bar shows the title. */
    Normal,

    /** The search bar is being edited over the list, with its suggestions. */
    Simple,

    /** The advanced search layout, without suggestions (no keyboard). */
    Advanced,

    /** The advanced search layout with the search bar's suggestions. */
    AdvancedList;

    /** The search bar's suggestion list is up: the drawers stay closed meanwhile. */
    val showsSuggestions: Boolean get() = this == Simple || this == AdvancedList

    /** The advanced search layout covers the list. */
    val showsSearchLayout: Boolean get() = this == Advanced || this == AdvancedList

    /** Where a back goes from here, or null when a back has nothing to undo in search. */
    fun back(): SearchChrome? = when (this) {
        Simple, Advanced -> Normal
        AdvancedList -> Advanced
        Normal -> null
    }
}
