/*
 * Copyright 2016 Hippo Seven
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

package com.lanraragi.reader.widget

import android.content.Context
import android.util.AttributeSet
import com.hippo.drawerlayout.DrawerLayoutChild
import com.lanraragi.framework.scene.StageLayout

class AppStageLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : StageLayout(context, attrs, defStyleAttr), DrawerLayoutChild {

    private var windowPaddingTop = 0
    private var windowPaddingBottom = 0

    override fun onGetWindowPadding(top: Int, bottom: Int) {
        if (top == windowPaddingTop && bottom == windowPaddingBottom) return
        windowPaddingTop = top
        windowPaddingBottom = bottom
        // The drawer reads these margins in onMeasure but never re-lays out
        // when the insets change (e.g. the keyboard opens, RES-1).
        requestLayout()
    }

    override fun getAdditionalTopMargin(): Int = windowPaddingTop

    override fun getAdditionalBottomMargin(): Int = windowPaddingBottom
}
