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

package com.lanraragi.framework.lib.glview.view

import android.content.Context
import android.graphics.Matrix
import com.lanraragi.framework.lib.glview.anim.CanvasAnimation
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas

interface GLRoot {

    // Listener will be called when GL is idle AND before each frame.
    // Mainly used for uploading textures.
    fun interface OnGLIdleListener {
        // canvas is null when the listener runs before the GL surface (and so
        // the canvas) has been created.
        fun onGLIdle(canvas: GLCanvas?, renderRequested: Boolean): Boolean
    }

    fun addOnGLIdleListener(listener: OnGLIdleListener)
    fun registerLaunchedAnimation(animation: CanvasAnimation)
    fun requestRenderForced()
    fun requestRender()
    fun requestLayoutContentPane()

    fun lockRenderThread()
    fun unlockRenderThread()

    // null detaches the current content pane.
    fun setContentPane(content: GLView?)
    fun setOrientationSource(source: OrientationSource?)
    fun getDisplayRotation(): Int
    fun getCompensation(): Int
    fun getCompensationMatrix(): Matrix
    fun freeze()
    fun unfreeze()
    fun setLightsOutMode(enabled: Boolean)

    // Functions, not properties: GLRootView gets these from View's final getters.
    fun getContext(): Context

    fun getWidth(): Int
    fun getHeight(): Int
}
