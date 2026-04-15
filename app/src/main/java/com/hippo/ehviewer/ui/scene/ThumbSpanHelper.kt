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

package com.hippo.ehviewer.ui.scene

import android.util.Log
import com.lanraragi.reader.domain.Archive
import java.util.Arrays

// KNOWN-ISSUE (P2): GridLayoutManager ignores SpaceGroupIndex; custom span calculation needed
class ThumbSpanHelper(private val mData: List<Archive>) {

    companion object {
        private const val MIN_ARRAY_LENGTH = 50
        private const val MAX_ROW_INTERVAL = 3
    }

    // true for occupied, false for free
    private var mCells = BooleanArray(MIN_ARRAY_LENGTH)

    private var mNextIndex = 0
    private var mNextGroupIndex = 0
    private var mNearSpaceIndex = 0
    private var mNearSpaceGroupIndex = 0
    private var mAttachedCount = 0

    private var mSpanCount = 0

    private var mEnable = false

    fun setEnable(enable: Boolean) {
        if (enable == mEnable) {
            return
        }
        mEnable = enable

        if (!enable) {
            clear()
            mSpanCount = 0
        } else {
            if (mSpanCount > 0) {
                rebuild()
            }
        }
    }

    fun setSpanCount(spanCount: Int) {
        if (!mEnable) {
            return
        }

        if (spanCount == mSpanCount) {
            return
        }
        mSpanCount = spanCount
        if (spanCount > 0) {
            rebuild()
        }
    }

    fun notifyDataSetChanged() {
        if (!mEnable || mSpanCount <= 0) {
            return
        }
        rebuild()
    }

    fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int) {
        if (!mEnable || mSpanCount <= 0) {
            return
        }
        rebuild()
    }

    protected fun notifyItemRangeInserted(positionStart: Int, itemCount: Int) {
        if (!mEnable || mSpanCount <= 0) {
            return
        }
        if (mAttachedCount == positionStart) {
            append()
        } else {
            rebuild()
        }
    }

    private fun clear() {
        Log.d("TAG", "=======================clear=======================")

        if (mSpanCount > 0) {
            if (mNextGroupIndex > 0 || mNextIndex > 0) {
                Arrays.fill(mCells, 0, mNextGroupIndex * mSpanCount + mNextIndex, false)
            }
        } else {
            Arrays.fill(mCells, false)
        }
        mNextIndex = 0
        mNextGroupIndex = 0
        mNearSpaceIndex = 0
        mNearSpaceGroupIndex = 0
        mAttachedCount = 0
    }

    private fun rebuild() {
        clear()
        append()
    }

    private fun append() {
        // Archive is an immutable data class — span fields cannot be mutated in-place.
        // LANraragi thumbnails are uniform size, so staggered spanning is not needed.
        mAttachedCount = mData.size
    }

    private fun updateNearSpace() {
        // Check is space is near enough
        if (mNextGroupIndex - mNearSpaceGroupIndex <= MAX_ROW_INTERVAL) {
            return
        }

        // The space is too far, find a near one
        val start = maxOf(0, mNextGroupIndex - MAX_ROW_INTERVAL) * mSpanCount
        findNearSpace(start)
    }

    private fun findNearSpace(start: Int) {
        val end = mNextGroupIndex * mSpanCount + mNextIndex
        val cells = mCells
        for (i in start until end) {
            if (!cells[i]) {
                // Find space !
                mNearSpaceIndex = i % mSpanCount
                mNearSpaceGroupIndex = i / mSpanCount
                return
            }
        }
        // Can't find space
        mNearSpaceIndex = mNextIndex
        mNearSpaceGroupIndex = mNextGroupIndex
    }

    private fun fillCell(start: Int, end: Int) {
        var array = mCells
        // Avoid IndexOutOfBoundsException
        if (end >= array.size) {
            val newArray = BooleanArray(end + if (end < MIN_ARRAY_LENGTH / 2) MIN_ARRAY_LENGTH else end shr 1)
            System.arraycopy(array, 0, newArray, 0, array.size)
            mCells = newArray
            array = newArray
        }
        Arrays.fill(array, start, end, true)
    }
}
