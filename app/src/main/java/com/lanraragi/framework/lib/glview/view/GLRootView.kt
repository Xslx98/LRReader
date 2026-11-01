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

@file:Suppress( // TODO(2.4-idiom)
    "ObjectLiteralToLambda",
    "UnusedParameter",
    "UnsafeCallOnNullableType",
    "NestedBlockDepth",
    "ThrowsCount",
    "UseRequire",
)

package com.lanraragi.framework.lib.glview.view

import android.content.Context
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import com.lanraragi.framework.lib.glview.anim.CanvasAnimation
import com.lanraragi.framework.lib.glview.glrenderer.BasicTexture
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.GLES20Canvas
import com.lanraragi.framework.lib.glview.glrenderer.UploadedTexture
import com.lanraragi.framework.lib.glview.util.GalleryUtils
import com.lanraragi.framework.lib.glview.util.MotionEventHelper
import com.lanraragi.framework.lib.glview.view.GLRoot.OnGLIdleListener
import com.lanraragi.reader.util.AssertUtils
import com.lanraragi.reader.util.TraceSections
import java.util.ArrayDeque
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.opengles.GL11

// UPSTREAM-LEGACY (Gallery2/AOSP): attachToRoot/detachFromRoot should run in render thread
// The root component of all <code>GLView</code>s. The rendering is done in GL
// thread while the event handling is done in the main thread.  To synchronize
// the two threads, the entry points of this package need to synchronize on the
// <code>GLRootView</code> instance unless it can be proved that the rendering
// thread won't access the same thing as the method. The entry points include:
// (1) The public methods of HeadUpDisplay
// (2) The public methods of CameraHeadUpDisplay
// (3) The overridden methods in GLRootView.
class GLRootView(context: Context, attrs: AttributeSet?) : GLSurfaceView(context, attrs), GLRoot {

    private var frameCount = 0
    private var frameCountingStart = 0L

    private var invalidateColor = 0

    private var gl11: GL11? = null
    private var canvas: GLCanvas? = null
    private var contentView: GLView? = null

    private var orientationSource: OrientationSource? = null

    // compensationDegrees is the difference between the UI orientation on GLCanvas
    // and the framework orientation. See OrientationManager for details.
    // Private fields behind the GLRoot properties: the frame and touch paths
    // read them directly, not through the interface getters.
    private var compensationDegrees = 0

    // touchCompensation maps the coordinates of touch events. It is kept sync
    // with compensationDegrees.
    private val touchCompensation = Matrix()

    private var currentDisplayRotation = 0

    override val displayRotation: Int
        get() = currentDisplayRotation

    override val compensation: Int
        get() = compensationDegrees

    override val compensationMatrix: Matrix
        get() = touchCompensation

    private var flags = FLAG_NEED_LAYOUT

    @Volatile
    private var renderRequested = false

    private val animations = ArrayList<CanvasAnimation>()

    private val idleListeners = ArrayDeque<OnGLIdleListener>()

    private val idleRunner = IdleRunner()

    private val renderLock = ReentrantLock()
    private val freezeCondition: Condition = renderLock.newCondition()
    private var frozen = false

    private var inDownState = false

    private val requestRenderOnAnimationFrame = object : Runnable {
        override fun run() {
            superRequestRender()
        }
    }

    constructor(context: Context) : this(context, null)

    init {
        flags = flags or FLAG_INITIALIZED
        @Suppress("DEPRECATION")
        setBackgroundDrawable(null)

        val eglContextClientVersion = 2
        setEGLContextClientVersion(eglContextClientVersion)
        setEGLConfigChooser(ConfigChooser())
        setRenderer(GLRootRenderer())
        holder.setFormat(PixelFormat.RGB_888)

        // Uncomment this to enable gl error check.
        // setDebugFlags(DEBUG_CHECK_GL_ERROR);
    }

    override fun registerLaunchedAnimation(animation: CanvasAnimation) {
        // Register the newly launched animation so that we can set the start
        // time more precisely. (Usually, it takes much longer for first
        // rendering, so we set the animation start time as the time we
        // complete rendering)
        animations.add(animation)
    }

    override fun addOnGLIdleListener(listener: OnGLIdleListener) {
        synchronized(idleListeners) {
            idleListeners.addLast(listener)
            if (canvas != null) {
                // Wait for onSurfaceCreated
                idleRunner.enable()
            }
        }
    }

    override fun setContentPane(content: GLView?) {
        if (contentView === content) return
        val oldContentView = contentView
        if (oldContentView != null) {
            if (inDownState) {
                val now = SystemClock.uptimeMillis()
                val cancelEvent = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0
                )
                oldContentView.dispatchTouchEvent(cancelEvent)
                cancelEvent.recycle()
                inDownState = false
            }
            oldContentView.detachFromRoot()
            BasicTexture.yieldAllTextures()
        }
        contentView = content
        if (content != null) {
            content.attachToRoot(this)
            requestLayoutContentPane()
        }
    }

    override fun requestRenderForced() {
        superRequestRender()
    }

    override fun requestRender() {
        if (DEBUG_INVALIDATE) {
            val e = Thread.currentThread().stackTrace[4]
            val caller = e.fileName + ":" + e.lineNumber + " "
            Log.d(TAG, "invalidate: $caller")
        }
        if (renderRequested) return
        renderRequested = true
        postOnAnimation(requestRenderOnAnimationFrame)
    }

    private fun superRequestRender() {
        super<GLSurfaceView>.requestRender()
    }

    override fun requestLayoutContentPane() {
        renderLock.lock()
        try {
            if (contentView == null || (flags and FLAG_NEED_LAYOUT) != 0) return

            // "View" system will invoke onLayout() for initialization(bug ?), we
            // have to ignore it since the GLThread is not ready yet.
            if ((flags and FLAG_INITIALIZED) == 0) return

            flags = flags or FLAG_NEED_LAYOUT
            requestRender()
        } finally {
            renderLock.unlock()
        }
    }

    private fun layoutContentPane() {
        flags = flags and FLAG_NEED_LAYOUT.inv()

        var w = width
        var h = height
        val newDisplayRotation: Int
        val newCompensation: Int

        // Get the new orientation values
        val orientationSource = this.orientationSource
        if (orientationSource != null) {
            newDisplayRotation = orientationSource.displayRotation
            newCompensation = orientationSource.compensation
        } else {
            newDisplayRotation = 0
            newCompensation = 0
        }

        if (compensationDegrees != newCompensation) {
            compensationDegrees = newCompensation
            if (compensationDegrees % 180 != 0) {
                touchCompensation.setRotate(compensationDegrees.toFloat())
                // move center to origin before rotation
                touchCompensation.preTranslate((-w / 2).toFloat(), (-h / 2).toFloat())
                // align with the new origin after rotation
                touchCompensation.postTranslate((h / 2).toFloat(), (w / 2).toFloat())
            } else {
                touchCompensation.setRotate(compensationDegrees.toFloat(), (w / 2).toFloat(), (h / 2).toFloat())
            }
        }
        currentDisplayRotation = newDisplayRotation

        // Do the actual layout.
        if (compensationDegrees % 180 != 0) {
            val tmp = w
            w = h
            h = tmp
        }
        Log.i(TAG, "layout content pane " + w + "x" + h + " (compensation " + compensationDegrees + ")")
        val contentView = this.contentView
        if (contentView != null && w != 0 && h != 0) {
            contentView.measure(
                GLView.MeasureSpec.makeMeasureSpec(w, GLView.MeasureSpec.EXACTLY),
                GLView.MeasureSpec.makeMeasureSpec(h, GLView.MeasureSpec.EXACTLY)
            )
            contentView.layout(0, 0, w, h)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (changed) requestLayoutContentPane()
    }

    private fun outputFps() {
        val now = System.nanoTime()
        if (frameCountingStart == 0L) {
            frameCountingStart = now
        } else if ((now - frameCountingStart) > 1000000000) {
            Log.d(
                TAG,
                "fps: " + frameCount.toDouble() * 1000000000 / (now - frameCountingStart)
            )
            frameCountingStart = now
            frameCount = 0
        }
        ++frameCount
    }

    private fun onDrawFrameLocked(gl: GL10) {
        if (DEBUG_FPS) outputFps()
        val canvas = this.canvas!!
        // release the unbound textures and deleted buffers.
        canvas.deleteRecycledResources()

        // reset texture upload limit
        UploadedTexture.resetUploadLimit()

        renderRequested = false

        val orientationSource = this.orientationSource
        if ((orientationSource != null &&
                currentDisplayRotation != orientationSource.displayRotation) ||
            (flags and FLAG_NEED_LAYOUT) != 0
        ) {
            layoutContentPane()
        }

        canvas.save(GLCanvas.SAVE_FLAG_ALL)
        rotateCanvas(-compensationDegrees)
        val contentView = this.contentView
        if (contentView != null) {
            contentView.render(canvas)
        } else {
            // Make sure we always draw something to prevent displaying garbage
            canvas.clearBuffer()
        }
        canvas.restore()

        if (!animations.isEmpty()) {
            val now = AnimationTime.get()
            for (i in 0 until animations.size) {
                animations[i].setStartTime(now)
            }
            animations.clear()
        }

        if (UploadedTexture.uploadLimitReached()) {
            requestRender()
        }

        synchronized(idleListeners) {
            if (!idleListeners.isEmpty()) idleRunner.enable()
        }

        if (DEBUG_INVALIDATE) {
            canvas.fillRect(10f, 10f, 5f, 5f, invalidateColor)
            invalidateColor = invalidateColor.inv()
        }

        if (DEBUG_DRAWING_STAT) {
            canvas.dumpStatisticsAndClear()
        }
    }

    private fun rotateCanvas(degrees: Int) {
        if (degrees == 0) return
        val canvas = this.canvas!!
        val w = width
        val h = height
        val cx = w / 2
        val cy = h / 2
        canvas.translate(cx.toFloat(), cy.toFloat())
        canvas.rotate(degrees.toFloat(), 0f, 0f, 1f)
        if (degrees % 180 != 0) {
            canvas.translate((-cy).toFloat(), (-cx).toFloat())
        } else {
            canvas.translate((-cx).toFloat(), (-cy).toFloat())
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val action = event.action
        if (action == MotionEvent.ACTION_CANCEL ||
            action == MotionEvent.ACTION_UP
        ) {
            inDownState = false
        } else if (!inDownState && action != MotionEvent.ACTION_DOWN) {
            return false
        }

        var transformedEvent = event
        if (compensationDegrees != 0) {
            transformedEvent = MotionEventHelper.transformEvent(event, touchCompensation)
        }

        renderLock.lock()
        try {
            // If this has been detached from root, we don't need to handle event
            val contentView = this.contentView
            val handled = contentView != null &&
                contentView.dispatchTouchEvent(transformedEvent)
            if (action == MotionEvent.ACTION_DOWN && handled) {
                inDownState = true
            }
            return handled
        } finally {
            renderLock.unlock()
        }
    }

    override fun lockRenderThread() {
        renderLock.lock()
    }

    override fun unlockRenderThread() {
        renderLock.unlock()
    }

    override fun onPause() {
        unfreeze()
        super.onPause()
    }

    override fun setOrientationSource(source: OrientationSource?) {
        orientationSource = source
    }

    override fun freeze() {
        renderLock.lock()
        frozen = true
        renderLock.unlock()
    }

    override fun unfreeze() {
        renderLock.lock()
        frozen = false
        freezeCondition.signalAll()
        renderLock.unlock()
    }

    @Suppress("DEPRECATION")
    override fun setLightsOutMode(enabled: Boolean) {
        var uiFlags = 0
        if (enabled) {
            uiFlags = View.STATUS_BAR_HIDDEN
            uiFlags = uiFlags or (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
        systemUiVisibility = uiFlags
    }

    // We need to unfreeze in the following methods and in onPause().
    // These methods will wait on GLThread. If we have freezed the GLRootView,
    // the GLThread will wait on main thread to call unfreeze and cause dead
    // lock.
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        unfreeze()
        super.surfaceChanged(holder, format, w, h)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        unfreeze()
        super.surfaceCreated(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        unfreeze()
        super.surfaceDestroyed(holder)
    }

    override fun onDetachedFromWindow() {
        unfreeze()
        super.onDetachedFromWindow()
    }

    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) {
        super.dispatchSaveInstanceState(container)
        contentView?.saveHierarchyState(container)
    }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) {
        super.dispatchRestoreInstanceState(container)
        contentView?.restoreHierarchyState(container)
    }

    @Throws(Throwable::class)
    override fun finalize() {
        try {
            unfreeze()
        } finally {
            super.finalize()
        }
    }

    private inner class GLRootRenderer : Renderer {

        /**
         * Called when the context is created, possibly after automatic destruction.
         */
        override fun onSurfaceCreated(gl1: GL10, config: EGLConfig) {
            val gl = gl1 as GL11
            if (gl11 != null) {
                // The GL Object has changed
                Log.i(TAG, "GLObject has changed from $gl11 to $gl")
            }
            renderLock.lock()
            try {
                gl11 = gl
                canvas = GLES20Canvas()
                BasicTexture.invalidateAllTextures()
            } finally {
                renderLock.unlock()
            }

            if (DEBUG_FPS) {
                renderMode = RENDERMODE_CONTINUOUSLY
            } else {
                renderMode = RENDERMODE_WHEN_DIRTY
            }
        }

        /**
         * Called when the OpenGL surface is recreated without destroying the
         * context.
         */
        override fun onSurfaceChanged(gl1: GL10, width: Int, height: Int) {
            Log.i(TAG, "onSurfaceChanged: " + width + "x" + height + ", gl10: " + gl1.toString())
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            GalleryUtils.setRenderThread()
            val gl = gl1 as GL11
            AssertUtils.assertTrue(gl11 === gl)

            canvas!!.setSize(width, height)
        }

        override fun onDrawFrame(gl: GL10) {
            AnimationTime.update()

            val t0 = System.nanoTime()

            renderLock.lock()

            while (frozen) {
                freezeCondition.awaitUninterruptibly()
            }

            // Perf trace section: GLSurfaceView frames never reach HWUI's
            // frame stats (see com.lanraragi.reader.util.TraceSections).
            Trace.beginSection(TraceSections.READER_GL_FRAME)
            try {
                onDrawFrameLocked(gl)
            } finally {
                Trace.endSection()
                renderLock.unlock()
            }

            val t = System.nanoTime()
            val duration = (t - t0) / 1000000

            if (duration > 50) {
                Log.v(TAG, "--- $duration ---")
            }
        }
    }

    private inner class IdleRunner : Runnable {
        // true if the idle runner is in the queue
        private var active = false

        override fun run() {
            val listener: OnGLIdleListener
            synchronized(idleListeners) {
                active = false
                if (idleListeners.isEmpty()) return
                listener = idleListeners.removeFirst()
            }
            renderLock.lock()
            val keepInQueue = try {
                listener.onGLIdle(canvas, renderRequested)
            } finally {
                renderLock.unlock()
            }
            synchronized(idleListeners) {
                if (keepInQueue) idleListeners.addLast(listener)
                if (!renderRequested && !idleListeners.isEmpty()) enable()
            }
        }

        fun enable() {
            // Who gets the flag can add it to the queue
            if (active) return
            active = true
            queueEvent(this)
        }
    }

    // Always chose a config
    private class ConfigChooser : EGLConfigChooser {

        private val value = IntArray(1)

        override fun chooseConfig(egl: EGL10, display: EGLDisplay?): EGLConfig {
            // Check if display is valid
            if (display == null || display === EGL10.EGL_NO_DISPLAY) {
                val error = egl.eglGetError()
                throw IllegalArgumentException(
                    "Invalid EGL display, error: 0x" + Integer.toHexString(error)
                )
            }

            // Some vendor EGL drivers sporadically report EGL_NOT_INITIALIZED here.
            // Retry once after explicit eglInitialize to avoid startup crash.
            for (attempt in 0 until 2) {
                if (!ensureDisplayInitialized(egl, display)) {
                    val error = egl.eglGetError()
                    throw IllegalArgumentException(
                        "EGL display not initialized, error: 0x" + Integer.toHexString(error)
                    )
                }

                try {
                    return chooseConfigInternal(egl, display)
                } catch (e: IllegalArgumentException) {
                    val error = egl.eglGetError()
                    if (attempt == 0 && error == EGL_NOT_INITIALIZED) {
                        Log.w(TAG, "eglChooseConfig hit EGL_NOT_INITIALIZED, retrying eglInitialize once")
                        if (tryInitializeDisplay(egl, display)) {
                            continue
                        }
                    }
                    throw e
                }
            }

            throw IllegalArgumentException("eglChooseConfig failed after reinitialize retry")
        }

        private fun ensureDisplayInitialized(egl: EGL10, display: EGLDisplay): Boolean {
            val numConfig = IntArray(1)
            if (egl.eglGetConfigs(display, null, 0, numConfig)) {
                return true
            }
            val error = egl.eglGetError()
            if (error != EGL_NOT_INITIALIZED) {
                return false
            }
            return tryInitializeDisplay(egl, display) &&
                egl.eglGetConfigs(display, null, 0, numConfig)
        }

        private fun tryInitializeDisplay(egl: EGL10, display: EGLDisplay): Boolean {
            val version = IntArray(2)
            val initialized = egl.eglInitialize(display, version)
            if (!initialized) {
                val error = egl.eglGetError()
                Log.w(TAG, "eglInitialize retry failed, error: 0x" + Integer.toHexString(error))
            }
            return initialized
        }

        private fun chooseConfigInternal(egl: EGL10, display: EGLDisplay): EGLConfig {
            val numConfig = IntArray(1)
            var configSpec: IntArray
            var error: Int

            // First try: basic config without RENDERABLE_TYPE (most compatible)
            configSpec = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_NONE
            )

            // On success the basic config is used.
            if (!(egl.eglChooseConfig(display, configSpec, null, 0, numConfig) && numConfig[0] > 0)) {
                error = egl.eglGetError()
                Log.w(TAG, "eglChooseConfig (basic) failed with error: 0x" + Integer.toHexString(error))

                // Second try: OpenGL ES 2.0 compatible config
                // Use EGL_OPENGL_ES2_BIT constant (0x0004) but only if supported
                configSpec = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_NONE
                )

                // On success the ES 2.0 config is used.
                if (!(egl.eglChooseConfig(display, configSpec, null, 0, numConfig) && numConfig[0] > 0)) {
                    error = egl.eglGetError()
                    Log.w(TAG, "eglChooseConfig (ES2) failed with error: 0x" + Integer.toHexString(error))

                    // Third try: minimal config without RENDERABLE_TYPE
                    configSpec = intArrayOf(
                        EGL10.EGL_RED_SIZE, 5,
                        EGL10.EGL_GREEN_SIZE, 6,
                        EGL10.EGL_BLUE_SIZE, 5,
                        EGL10.EGL_ALPHA_SIZE, 0,
                        EGL10.EGL_NONE
                    )

                    // On success the minimal config is used.
                    if (!(egl.eglChooseConfig(display, configSpec, null, 0, numConfig) && numConfig[0] > 0)) {
                        error = egl.eglGetError()
                        Log.w(TAG, "eglChooseConfig (minimal) failed with error: 0x" + Integer.toHexString(error))

                        // Last resort: try with EGL_NONE only (let system choose)
                        configSpec = intArrayOf(EGL10.EGL_NONE)
                        if (!egl.eglChooseConfig(display, configSpec, null, 0, numConfig)) {
                            error = egl.eglGetError()
                            throw IllegalArgumentException(
                                "eglChooseConfig failed with error: 0x" + Integer.toHexString(error)
                            )
                        }
                    }
                }
            }

            val numConfigs = numConfig[0]

            if (numConfigs <= 0) {
                throw IllegalArgumentException(
                    "No configs match configSpec"
                )
            }

            val configs = arrayOfNulls<EGLConfig>(numConfigs)
            if (!egl.eglChooseConfig(display, configSpec, configs, numConfigs, numConfig)) {
                error = egl.eglGetError()
                throw IllegalArgumentException(
                    "eglChooseConfig#2 failed with error: 0x" + Integer.toHexString(error)
                )
            }
            val config = chooseConfig(egl, display, configs)
            if (config == null) {
                throw IllegalArgumentException("No config chosen")
            }
            return config
        }

        private fun chooseConfig(egl: EGL10, display: EGLDisplay, configs: Array<EGLConfig?>): EGLConfig? {
            // Use score to avoid "No config chosen"
            var configIndex = 0
            var maxScore = 0

            for (i in configs.indices) {
                val config = configs[i]
                val redSize = findConfigAttrib(egl, display, config, EGL10.EGL_RED_SIZE)
                val greenSize = findConfigAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE)
                val blueSize = findConfigAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE)
                val alphaSize = findConfigAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE)
                val sampleBuffers = findConfigAttrib(egl, display, config, EGL10.EGL_SAMPLE_BUFFERS)
                val samples = findConfigAttrib(egl, display, config, EGL10.EGL_SAMPLES)
                val depth = findConfigAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE)
                val stencil = findConfigAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE)

                val score = redSize + greenSize + blueSize + alphaSize +
                    sampleBuffers + samples - depth - stencil

                if (score > maxScore) {
                    maxScore = score
                    configIndex = i
                }
            }

            return configs[configIndex]
        }

        private fun findConfigAttrib(
            egl: EGL10,
            display: EGLDisplay,
            config: EGLConfig?,
            attribute: Int
        ): Int {
            if (egl.eglGetConfigAttrib(display, config, attribute, value)) {
                return value[0]
            }
            return 0
        }

        companion object {
            private const val EGL_OPENGL_ES2_BIT = 0x0004
            private const val EGL_NOT_INITIALIZED = 0x3001
        }
    }

    companion object {
        private const val TAG = "GLRootView"

        private const val DEBUG_FPS = false

        private const val DEBUG_INVALIDATE = false

        private const val DEBUG_DRAWING_STAT = false

        private const val FLAG_INITIALIZED = 1
        private const val FLAG_NEED_LAYOUT = 2
    }
}
