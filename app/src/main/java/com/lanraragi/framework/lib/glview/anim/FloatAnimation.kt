/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.lanraragi.framework.lib.glview.anim

import com.lanraragi.reader.util.MathUtils

open class FloatAnimation : Animation() {

    private var mFrom = 0f
    private var mTo = 0f
    private var mCurrent = 0f

    fun setRange(from: Float, to: Float) {
        mFrom = from
        mTo = to
    }

    override fun onCalculate(progress: Float) {
        mCurrent = MathUtils.lerp(mFrom, mTo, progress)
    }

    fun get(): Float {
        return mCurrent
    }
}
