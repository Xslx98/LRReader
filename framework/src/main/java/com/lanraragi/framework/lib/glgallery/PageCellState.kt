/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.framework.lib.glgallery

/**
 * What one reader page cell shows ([GalleryPageView.state]). The cell's child
 * views follow it ([GalleryPageView.render]); nothing reads their visibility
 * back to learn what the page is doing. Plain classes, not data classes:
 * nothing compares or prints them in the app, and the reader is size-budgeted.
 */
sealed interface PageCellState {
    /** Requested, nothing known yet: the page number and an indeterminate spinner. */
    object Wait : PageCellState

    /** Loading, [fraction] (0..1) done: the page number and a determinate ring. */
    class Progress(val fraction: Float) : PageCellState

    /** The image is shown. */
    object Loaded : PageCellState

    /** Failed: the page number and [message] as tappable error text (none when null). */
    class Error(val message: String?) : PageCellState
}
