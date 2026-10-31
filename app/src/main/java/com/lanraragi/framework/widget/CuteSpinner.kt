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

package com.lanraragi.framework.widget

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.widget.ArrayAdapter
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.content.withStyledAttributes
import com.lanraragi.reader.R

class CuteSpinner : AppCompatSpinner {

    constructor(context: Context) : super(context) {
        init(context, null, androidx.appcompat.R.attr.spinnerStyle)
    }

    constructor(context: Context, mode: Int) : super(context, mode) {
        init(context, null, androidx.appcompat.R.attr.spinnerStyle)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs, androidx.appcompat.R.attr.spinnerStyle)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs, defStyleAttr)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, mode: Int) :
        super(context, attrs, defStyleAttr, mode) {
        init(context, attrs, defStyleAttr)
    }

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        mode: Int,
        popupTheme: Resources.Theme?,
    ) : super(context, attrs, defStyleAttr, mode, popupTheme) {
        init(context, attrs, defStyleAttr)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        context.withStyledAttributes(attrs, androidx.appcompat.R.styleable.Spinner, defStyleAttr, 0) {
            val entries = getTextArray(androidx.appcompat.R.styleable.Spinner_android_entries)
            if (entries != null) {
                val adapter = ArrayAdapter(context, R.layout.item_cute_spinner_item, entries)
                adapter.setDropDownViewResource(androidx.appcompat.R.layout.support_simple_spinner_dropdown_item)
                setAdapter(adapter)
            }
        }
    }
}
