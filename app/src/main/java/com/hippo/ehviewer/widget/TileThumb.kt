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
import com.hippo.lib.yorozuya.MathUtils
import com.hippo.widget.LoadImageView

class TileThumb @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LoadImageView(context, attrs, defStyleAttr) {

    fun setThumbSize(thumbWidth: Int, thumbHeight: Int) {
        val aspect = if (thumbWidth > 0 && thumbHeight > 0) {
            MathUtils.clamp(thumbWidth / thumbHeight.toFloat(), MIN_ASPECT, MAX_ASPECT)
        } else {
            DEFAULT_ASPECT
        }
        setAspect(aspect)
    }

    companion object {
        private const val MIN_ASPECT = 0.33f
        private const val MAX_ASPECT = 1.5f
        private const val DEFAULT_ASPECT = 0.67f
    }
}
