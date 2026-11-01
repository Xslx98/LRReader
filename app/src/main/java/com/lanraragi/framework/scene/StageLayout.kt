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

@file:Suppress("PrintStackTrace") // TODO(2.4-idiom)

package com.lanraragi.framework.scene

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lanraragi.reader.R
import java.lang.reflect.Field

open class StageLayout : FrameLayout {

    private var mDisappearingChildrenField: Field? = null
    private var mSuperDisappearingChildren: ArrayList<View>? = null
    private var mSortedScenes: ArrayList<View>? = null
    private var mDumpView: View? = null
    private var mDoTrick = false

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    @SuppressLint("DiscouragedPrivateApi") // the scene draw order needs the disappearing children; no field, no trick
    private fun init(context: Context) {
        try {
            val field = ViewGroup::class.java.getDeclaredField("mDisappearingChildren")
            mDisappearingChildrenField = field
            field.isAccessible = true
        } catch (e: NoSuchFieldException) {
            e.printStackTrace()
        }

        if (mDisappearingChildrenField != null) {
            val dumpView = View(context)
            mDumpView = dumpView
            addView(dumpView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
    }

    @Suppress("UNCHECKED_CAST") // ViewGroup.mDisappearingChildren is an ArrayList<View>
    private fun getSuperDisappearingChildren() {
        val field = mDisappearingChildrenField
        if (field == null || mSuperDisappearingChildren != null) {
            return
        }

        try {
            mSuperDisappearingChildren = field.get(this) as ArrayList<View>?
        } catch (e: IllegalAccessException) {
            e.printStackTrace()
        }
    }

    private fun beforeDispatchDraw(): Boolean {
        getSuperDisappearingChildren()

        val disappearingChildren = mSuperDisappearingChildren
        if (disappearingChildren == null ||
            disappearingChildren.size <= 0 || childCount <= 1 // only dump view
        ) {
            return false
        }

        // Get stage
        val stage = context as? StageActivity ?: return false

        var sortedScenes = mSortedScenes
        if (null == sortedScenes) {
            sortedScenes = ArrayList()
            mSortedScenes = sortedScenes
        }

        // Add all scene view to mSortedScenes
        for (i in 1 until childCount) { // Skip dump view
            val view = getChildAt(i)
            if (null != view.getTag(R.id.fragment_tag)) {
                sortedScenes.add(view)
            }
        }
        for (i in 0 until disappearingChildren.size) {
            val view = disappearingChildren[i]
            if (null != view.getTag(R.id.fragment_tag)) {
                sortedScenes.add(view)
            }
        }

        stage.sortSceneViews(sortedScenes)

        return true
    }

    private fun afterDispatchDraw() {
        mSortedScenes?.clear()
    }

    override fun dispatchDraw(canvas: Canvas) {
        mDoTrick = beforeDispatchDraw()
        super.dispatchDraw(canvas)
        afterDispatchDraw()
        mDoTrick = false
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val sortedScenes = mSortedScenes
        // mDoTrick is only set once mSortedScenes exists.
        if (mDoTrick && sortedScenes != null) {
            if (child === mDumpView) {
                var more = false
                for (i in 0 until sortedScenes.size) {
                    more = more or super.drawChild(canvas, sortedScenes[i], drawingTime)
                }
                return more
            } else if (sortedScenes.contains(child)) {
                // Skip
                return false
            }
        }

        return super.drawChild(canvas, child, drawingTime)
    }
}
