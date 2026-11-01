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

package com.lanraragi.framework.scene

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lanraragi.reader.R
import java.lang.reflect.Field

open class StageLayout : FrameLayout {

    private var disappearingChildrenField: Field? = null
    private var superDisappearingChildren: ArrayList<View>? = null
    private var sortedScenes: ArrayList<View>? = null
    private var dumpView: View? = null
    private var doTrick = false

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    @SuppressLint("DiscouragedPrivateApi") // the scene draw order needs the disappearing children; no field, no trick
    private fun init(context: Context) {
        try {
            val field = ViewGroup::class.java.getDeclaredField("mDisappearingChildren")
            disappearingChildrenField = field
            field.isAccessible = true
        } catch (e: NoSuchFieldException) {
            Log.w(TAG, "No ViewGroup.mDisappearingChildren, scenes draw in child order", e)
        }

        if (disappearingChildrenField != null) {
            val view = View(context)
            dumpView = view
            addView(view, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
    }

    @Suppress("UNCHECKED_CAST") // ViewGroup.mDisappearingChildren is an ArrayList<View>
    private fun getSuperDisappearingChildren() {
        val field = disappearingChildrenField
        if (field == null || superDisappearingChildren != null) {
            return
        }

        try {
            superDisappearingChildren = field.get(this) as ArrayList<View>?
        } catch (e: IllegalAccessException) {
            Log.w(TAG, "Can't read ViewGroup.mDisappearingChildren", e)
        }
    }

    private fun beforeDispatchDraw(): Boolean {
        getSuperDisappearingChildren()

        val disappearingChildren = superDisappearingChildren
        if (disappearingChildren == null ||
            disappearingChildren.size <= 0 || childCount <= 1 // only dump view
        ) {
            return false
        }

        // Get stage
        val stage = context as? StageActivity ?: return false

        var scenes = sortedScenes
        if (null == scenes) {
            scenes = ArrayList()
            sortedScenes = scenes
        }

        // Add all scene view to sortedScenes
        for (i in 1 until childCount) { // Skip dump view
            val view = getChildAt(i)
            if (null != view.getTag(R.id.fragment_tag)) {
                scenes.add(view)
            }
        }
        for (i in 0 until disappearingChildren.size) {
            val view = disappearingChildren[i]
            if (null != view.getTag(R.id.fragment_tag)) {
                scenes.add(view)
            }
        }

        stage.sortSceneViews(scenes)

        return true
    }

    private fun afterDispatchDraw() {
        sortedScenes?.clear()
    }

    override fun dispatchDraw(canvas: Canvas) {
        doTrick = beforeDispatchDraw()
        super.dispatchDraw(canvas)
        afterDispatchDraw()
        doTrick = false
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val scenes = sortedScenes
        // doTrick is only set once sortedScenes exists.
        if (doTrick && scenes != null) {
            if (child === dumpView) {
                var more = false
                for (i in 0 until scenes.size) {
                    more = more or super.drawChild(canvas, scenes[i], drawingTime)
                }
                return more
            } else if (scenes.contains(child)) {
                // Skip
                return false
            }
        }

        return super.drawChild(canvas, child, drawingTime)
    }

    private companion object {
        const val TAG = "StageLayout"
    }
}
