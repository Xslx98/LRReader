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

package com.hippo.ehviewer.widget

import android.content.Context
import android.util.AttributeSet
import com.hippo.drawerlayout.DrawerLayoutChild
import com.hippo.widget.DrawerView

class EhDrawerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DrawerView(context, attrs, defStyleAttr), DrawerLayoutChild {

    private var windowPaddingTop = 0
    private var windowPaddingBottom = 0

    override fun onGetWindowPadding(top: Int, bottom: Int) {
        windowPaddingTop = top
        windowPaddingBottom = bottom
    }

    override fun getAdditionalTopMargin(): Int = windowPaddingTop

    override fun getAdditionalBottomMargin(): Int = windowPaddingBottom
}
