/*
 * Copyright 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lanraragi.reader.widget.list

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Even gaps between and around grid/list items: half the gap on every
 * side of each item, the rest as list padding ([applyPaddings]). Ported
 * to Kotlin from seven332's EasyRecyclerView library (0.1.1).
 */
class MarginItemDecoration(
    margin: Int,
    paddingLeft: Int,
    paddingTop: Int,
    paddingRight: Int,
    paddingBottom: Int
) : RecyclerView.ItemDecoration() {

    private var halfMargin = 0
    private var paddingLeft = 0
    private var paddingTop = 0
    private var paddingRight = 0
    private var paddingBottom = 0

    init {
        setMargin(margin, paddingLeft, paddingTop, paddingRight, paddingBottom)
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.set(halfMargin, halfMargin, halfMargin, halfMargin)
    }

    /**
     * [margin] is the gap between two items; the paddings are the gaps
     * between the list's edges and the outermost items.
     */
    fun setMargin(margin: Int, paddingLeft: Int, paddingTop: Int, paddingRight: Int, paddingBottom: Int) {
        val halfMargin = margin / 2
        this.halfMargin = halfMargin
        this.paddingLeft = paddingLeft - halfMargin
        this.paddingTop = paddingTop - halfMargin
        this.paddingRight = paddingRight - halfMargin
        this.paddingBottom = paddingBottom - halfMargin
    }

    fun applyPaddings(view: View) {
        view.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
    }
}
