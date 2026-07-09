/*
 * Copyright 2026 LR Reader
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.ui.scene.download.part

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the download-row title line budget.
 *
 * On a download row the byte-progress line (`finished/total` on the start
 * edge, speed on the end edge) is pinned above the bottom actions. A 2-line
 * title, anchored to the top of the fixed-height row, drops its second line
 * into that band and collides with the `finished/total` counter (both share
 * the title's start edge). The title is therefore capped to a single line
 * while progress is shown, and restored to two lines for every other state.
 */
class DownloadAdapterTitleLinesTest {

    @Test
    fun `title is capped to one line while progress is shown`() {
        assertEquals(1, DownloadAdapter.downloadTitleMaxLines(showingProgress = true))
    }

    @Test
    fun `title keeps two lines when progress is not shown`() {
        assertEquals(2, DownloadAdapter.downloadTitleMaxLines(showingProgress = false))
    }
}
