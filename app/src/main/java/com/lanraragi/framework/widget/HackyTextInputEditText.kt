/*
 * Copyright 2018 Hippo Seven
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

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.lanraragi.reader.util.ExceptionUtils
import java.lang.reflect.Field

// Avoid crash on some Meizu devices
// https://github.com/android-in-china/Compatibility/issues/11
// https://stackoverflow.com/questions/51891415/nullpointerexception-on-meizu-devices-in-editor-updatecursorpositionmz/52001305
@SuppressLint("PrivateApi", "SoonBlockedPrivateApi") // Meizu-only Editor probe and mHint read, try/catch-guarded
class HackyTextInputEditText(context: Context, attrs: AttributeSet?) : TextInputEditText(context, attrs) {

    override fun getHint(): CharSequence? {
        val fieldMHint = FIELD_M_HINT
        return if (HAS_METHOD_UPDATE_CURSOR_POSITION_MZ && fieldMHint != null) {
            try {
                getSuperHint(fieldMHint)
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
                super.getHint()
            }
        } else {
            super.getHint()
        }
    }

    @Throws(IllegalAccessException::class)
    private fun getSuperHint(fieldMHint: Field): CharSequence? = fieldMHint.get(this) as CharSequence?

    private companion object {
        private val HAS_METHOD_UPDATE_CURSOR_POSITION_MZ: Boolean
        private val FIELD_M_HINT: Field?

        init {
            var hasMethodUpdateCursorPositionMz = false
            try {
                val clazz = ClassLoader.getSystemClassLoader().loadClass("android.widget.Editor")
                val methods = clazz.declaredMethods
                for (method in methods) {
                    if ("updateCursorPositionMz" == method.name) {
                        hasMethodUpdateCursorPositionMz = true
                    }
                }
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
            }

            var fieldMHint: Field? = null
            if (hasMethodUpdateCursorPositionMz) {
                try {
                    fieldMHint = TextView::class.java.getDeclaredField("mHint")
                    fieldMHint.isAccessible = true
                } catch (e: Throwable) {
                    ExceptionUtils.throwIfFatal(e)
                }
            }

            HAS_METHOD_UPDATE_CURSOR_POSITION_MZ = hasMethodUpdateCursorPositionMz
            FIELD_M_HINT = fieldMHint
        }
    }
}
