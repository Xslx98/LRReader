/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatEditText
import com.hippo.util.ExceptionUtils

class SearchEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var mListener: SearchEditTextListener? = null

    fun setSearchEditTextListener(listener: SearchEditTextListener?) {
        mListener = listener
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val state = keyDispatcherState
                state?.startTracking(event, this)
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                val state = keyDispatcherState
                state?.handleUpEvent(event)
                if (event.isTracking && !event.isCanceled) {
                    // EH-LEGACY: stopSelectionActionMode not implemented
                    if (mListener != null) {
                        mListener!!.onBackPressed()
                        return true
                    }
                }
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && mListener != null) {
            mListener!!.onClick()
        }
        try {
            return super.onTouchEvent(event)
        } catch (t: Throwable) {
            // Some devices crash here.
            // I don't why.
            ExceptionUtils.throwIfFatal(t)
            return false
        }
    }

    interface SearchEditTextListener {
        fun onClick()
        fun onBackPressed()
    }
}
