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
    "UseCheckOrError",
    "ThrowingExceptionsWithoutMessageOrCause",
    "UnsafeCallOnNullableType",
    "LargeClass",
    "UseRequire",
    "ComplexCondition",
)

package com.lanraragi.framework.lib.glview.view

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import androidx.annotation.IntDef
import com.lanraragi.framework.lib.glview.anim.CanvasAnimation
import com.lanraragi.framework.lib.glview.glrenderer.GLCanvas
import com.lanraragi.framework.lib.glview.glrenderer.GLPaint
import com.lanraragi.reader.util.AssertUtils
import kotlin.math.max
import kotlin.math.min

// GLView is a UI component. It can render to a GLCanvas and accept touch
// events. A GLView may have zero or more child GLView and they form a tree
// structure. The rendering and event handling will pass through the tree
// structure.
//
// A GLView tree should be attached to a GLRoot before event dispatching and
// rendering happens. GLView asks GLRoot to re-render or re-layout the
// GLView hierarchy using requestRender() and requestLayoutContentPane().
//
// The render() method is called in a separate thread. Before calling
// dispatchTouchEvent() and layout(), GLRoot acquires a lock to avoid the
// rendering thread running at the same time. If there are other entry points
// from main thread (like a Handler) in your GLView, you need to call
// lockRendering() if the rendering thread should not run at the same time.
//
open class GLView : TouchOwner {

    @IntDef(GLView.VISIBLE, GLView.INVISIBLE, GLView.GONE)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Visibility

    fun interface OnClickListener {
        fun onClick(v: GLView): Boolean
    }

    fun interface OnLongClickListener {
        fun onLongClick(v: GLView): Boolean
    }

    /**
     * Position in parent, just like left, top, right, bottom in [android.view.View]
     */
    @JvmField
    protected val mBounds = Rect()

    /**
     * Position in root
     */
    private val positionInRoot = IntArray(2)

    @JvmField
    protected val mPaddings = Rect()

    private val tempRect = Rect()

    private var root: GLRoot? = null

    @JvmField
    protected var mParent: GLView? = null

    private var components: ArrayList<GLView>? = null
    private var motionTarget: GLView? = null

    private var animation: CanvasAnimation? = null

    private var viewFlags = 0

    @JvmField
    protected var mMeasuredWidth = 0

    @JvmField
    protected var mMeasuredHeight = 0

    private var lastWidthSpec = -1
    private var lastHeightSpec = -1

    @JvmField
    protected var mScrollY = 0

    @JvmField
    protected var mScrollX = 0

    @JvmField
    protected var mScrollHeight = 0

    @JvmField
    protected var mScrollWidth = 0

    /**
     * The GlView's identifier. The identifier does not have to be unique in
     * this GlView's hierarchy. The identifier should be a positive number, or
     * [NO_ID] if the view has no ID.
     */
    var id = NO_ID

    /**
     * The minimum height of the view. We'll try our best to have the height
     * of this view to at least this amount. It is not guaranteed the view will
     * be able to achieve this minimum height (for example, if its parent layout
     * constrains it with less available height).
     */
    var minimumHeight = 0
        set(minHeight) {
            field = minHeight
            requestLayout()
        }

    /**
     * The minimum width of the view. We'll try our best to have the width
     * of this view to at least this amount. It is not guaranteed the view will
     * be able to achieve this minimum width (for example, if its parent layout
     * constrains it with less available width).
     */
    var minimumWidth = 0
        set(minWidth) {
            field = minWidth
            requestLayout()
        }

    /**
     * Setting null takes the parent's default layout params; a view without a
     * parent cannot drop its params.
     */
    var layoutParams: LayoutParams? = null
        set(params) {
            var validParams = params
            if (validParams == null) {
                val parent = mParent ?: throw NullPointerException("Layout parameters cannot be null")
                validParams = parent.generateDefaultLayoutParams()
            }
            field = validParams
            requestLayout()
        }

    var backgroundColor = Color.TRANSPARENT

    private var touchHelper: TouchHelper? = null

    var hotspotX = 0f
        private set
    var hotspotY = 0f
        private set

    override var isPressed = false

    var onClickListener: OnClickListener? = null
    var onLongClickListener: OnLongClickListener? = null

    fun startAnimation(animation: CanvasAnimation?, atOnce: Boolean) {
        val root = getGLRoot() ?: throw IllegalStateException()
        this.animation = animation
        if (animation != null) {
            animation.start()
            if (atOnce) {
                animation.setStartTime(AnimationTime.get())
            } else {
                root.registerLaunchedAnimation(animation)
            }
        }
        invalidate()
    }

    /**
     * The visibility status of this view: one of [VISIBLE], [INVISIBLE], or [GONE].
     */
    @get:Visibility
    @get:SuppressLint("WrongConstant") // masked flags are VISIBLE, INVISIBLE or GONE once INVALID is mapped below
    var visibility: Int
        get() {
            var visibility = viewFlags and FLAG_INVISIBLE

            if (visibility == VISIBILITY_INVALID) {
                visibility = VISIBLE
                viewFlags = viewFlags and FLAG_INVISIBLE.inv()
                viewFlags = viewFlags or visibility
            }

            return visibility
        }
        set(@Visibility visibility) {
            @Visibility
            val oldVisibility = this.visibility
            if (visibility == oldVisibility) {
                return
            }

            viewFlags = viewFlags and FLAG_INVISIBLE.inv()
            viewFlags = viewFlags or visibility

            onVisibilityChanged(this, visibility)

            if (oldVisibility == GONE || visibility == GONE) {
                requestLayout()
            } else {
                invalidate()
            }
        }

    // This should only be called on the content pane (the topmost GLView).
    @JvmName("attachToRoot")
    internal fun attachToRoot(root: GLRoot) {
        AssertUtils.assertTrue(mParent == null && this.root == null)
        onAttachToRoot(root)
    }

    // This should only be called on the content pane (the topmost GLView).
    @JvmName("detachFromRoot")
    internal fun detachFromRoot() {
        AssertUtils.assertTrue(mParent == null && root != null)
        onDetachFromRoot()
    }

    // This should only be called on the content pane (the topmost GLView).
    @JvmName("pause")
    internal fun pause() {
        onPause()
    }

    // This should only be called on the content pane (the topmost GLView).
    @JvmName("resume")
    internal fun resume() {
        onResume()
    }

    val isAttachedToRoot: Boolean
        get() = root != null

    // Returns the number of children of the GLView.
    val componentCount: Int
        get() = components?.size ?: 0

    // Returns the children for the given index.
    fun getComponent(index: Int): GLView {
        val components = this.components
        if (components == null) throw ArrayIndexOutOfBoundsException(index)
        return components[index]
    }

    // Adds a child to this GLView.
    fun addComponent(component: GLView) {
        addComponent(component, -1, null)
    }

    fun addComponent(component: GLView, index: Int) {
        addComponent(component, index, null)
    }

    fun addComponent(component: GLView, params: LayoutParams?) {
        addComponent(component, -1, params)
    }

    fun addComponent(component: GLView, index: Int, params: LayoutParams?) {
        // Make sure the component doesn't have a parent currently.
        if (component.mParent != null) {
            throw IllegalStateException(
                "Component $this being added, but it already has a parent"
            )
        }

        // Ensure index is valid
        val validIndex = if (index < 0) componentCount else index

        // Ensure params is valid
        val validParams = if (params == null) {
            generateDefaultLayoutParams()
        } else if (!checkLayoutParams(params)) {
            generateLayoutParams(params)
        } else {
            params
        }

        // Build parent-child links
        var components = this.components
        if (components == null) {
            components = ArrayList()
            this.components = components
        }

        components.add(validIndex, component)
        component.mParent = this
        component.layoutParams = validParams

        // If this is added after we have a root, tell the component.
        val root = this.root
        if (root != null) {
            component.onAttachToRoot(root)
        }
    }

    // Removes a child from this GLView.
    fun removeComponent(component: GLView): Boolean {
        val components = this.components
        if (components == null) return false
        if (components.remove(component)) {
            removeOneComponent(component)
            return true
        }
        return false
    }

    fun removeComponentAt(index: Int): Boolean {
        val components = this.components
        if (components == null) {
            return false
        }
        if (index >= 0 && index < componentCount) {
            val component = components.removeAt(index)
            removeOneComponent(component)
            return true
        } else {
            return false
        }
    }

    // Removes all children of this GLView.
    fun removeAllComponents() {
        val components = this.components!!
        for (i in 0 until components.size) {
            removeOneComponent(components[i])
        }
        components.clear()
    }

    private fun removeOneComponent(component: GLView) {
        if (motionTarget === component) {
            val now = SystemClock.uptimeMillis()
            val cancelEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0
            )
            dispatchTouchEvent(cancelEvent)
            cancelEvent.recycle()
        }
        component.onDetachFromRoot()
        component.mParent = null
    }

    fun bounds(): Rect = mBounds

    override fun setHotspot(x: Float, y: Float) {
        hotspotX = x
        hotspotY = y
    }

    override val isEnabled: Boolean
        get() = true // dispatchTouchEvent: always consumed by GLView hierarchy

    override val isClickable: Boolean
        get() = onClickListener != null

    override val isLongClickable: Boolean
        get() = onLongClickListener != null

    override fun performClick(): Boolean {
        val listener = onClickListener
        if (listener != null) {
            return listener.onClick(this)
        } else {
            return false
        }
    }

    override fun performLongClick(): Boolean {
        val listener = onLongClickListener
        if (listener != null) {
            return listener.onLongClick(this)
        } else {
            return false
        }
    }

    override val width: Int
        get() = mBounds.right - mBounds.left

    override val height: Int
        get() = mBounds.bottom - mBounds.top

    /**
     * Offset this GLView's vertical location by the specified number of pixels.
     *
     * @param offset the number of pixels to offset the view by
     */
    fun offsetTopAndBottom(offset: Int) {
        if (offset != 0) {
            mBounds.offset(0, offset)
            dispatchNotifyPositionInRoot()
            invalidate()
        }
    }

    /**
     * Offset this GLView's horizontal location by the specified amount of pixels.
     *
     * @param offset the number of pixels to offset the view by
     */
    fun offsetLeftAndRight(offset: Int) {
        if (offset != 0) {
            mBounds.offset(offset, 0)
            dispatchNotifyPositionInRoot()
            invalidate()
        }
    }

    fun getGLRoot(): GLRoot? = root

    // Request re-rendering of the view hierarchy.
    // This is used for animation or when the contents changed.
    open fun invalidate() {
        val root = getGLRoot()
        root?.requestRender()
    }

    // Request re-layout of the view hierarchy.
    open fun requestLayout() {
        viewFlags = viewFlags or FLAG_LAYOUT_REQUESTED
        lastWidthSpec = -1
        lastHeightSpec = -1
        val parent = mParent
        if (parent != null) {
            parent.requestLayout()
        } else {
            // Is this a content pane ?
            val root = getGLRoot()
            root?.requestLayoutContentPane()
        }
    }

    open fun render(canvas: GLCanvas) {
        // render background color
        if (Color.alpha(backgroundColor) != 0) {
            canvas.fillRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundColor)
        }

        // render content
        onRender(canvas)

        // render child
        canvas.save()
        for (i in 0 until componentCount) {
            renderChild(canvas, getComponent(i))
        }
        canvas.restore()

        // render bounds
        if (DEBUG_DRAW_BOUNDS) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mDrawBoundsPaint)
        }
    }

    open fun onRender(canvas: GLCanvas) {
    }

    protected open fun renderChild(canvas: GLCanvas, component: GLView) {
        if (component.visibility != VISIBLE) {
            return
        }

        getValidRect(tempRect)
        if (tempRect.isEmpty) {
            return
        }

        val xOffset = component.mBounds.left - mScrollX
        val yOffset = component.mBounds.top - mScrollY

        canvas.translate(xOffset.toFloat(), yOffset.toFloat())

        val anim = component.animation
        if (anim != null) {
            canvas.save(anim.canvasSaveFlags)
            if (anim.calculate(AnimationTime.get())) {
                invalidate()
            } else {
                component.animation = null
            }
            anim.apply(canvas)
        }
        component.render(canvas)
        if (anim != null) canvas.restore()
        canvas.translate((-xOffset).toFloat(), (-yOffset).toFloat())
    }

    protected open fun onTouch(event: MotionEvent): Boolean {
        var touchHelper = this.touchHelper
        if (touchHelper == null) {
            touchHelper = TouchHelper(this)
            this.touchHelper = touchHelper
        }
        return touchHelper.onTouch(event)
    }

    protected open fun dispatchTouchEvent(
        event: MotionEvent,
        x: Int,
        y: Int,
        component: GLView,
        checkBounds: Boolean
    ): Boolean {
        val rect = component.mBounds
        val left = rect.left
        val top = rect.top
        if (!checkBounds || rect.contains(x, y)) {
            event.offsetLocation(-left.toFloat(), -top.toFloat())
            if (component.dispatchTouchEvent(event)) {
                event.offsetLocation(left.toFloat(), top.toFloat())
                return true
            }
            event.offsetLocation(left.toFloat(), top.toFloat())
        }
        return false
    }

    // Public, not protected: GLRootView dispatches into its content pane, and
    // Kotlin has no package-private access.
    open fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()
        val action = event.action
        val motionTarget = this.motionTarget
        if (motionTarget != null) {
            if (action == MotionEvent.ACTION_DOWN) {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                dispatchTouchEvent(cancel, x, y, motionTarget, false)
                this.motionTarget = null
            } else {
                dispatchTouchEvent(event, x, y, motionTarget, false)
                if (action == MotionEvent.ACTION_CANCEL ||
                    action == MotionEvent.ACTION_UP
                ) {
                    this.motionTarget = null
                }
                return true
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            // in the reverse rendering order
            for (i in componentCount - 1 downTo 0) {
                val component = getComponent(i)
                if (component.visibility != VISIBLE) continue
                if (dispatchTouchEvent(event, x, y, component, true)) {
                    this.motionTarget = component
                    return true
                }
            }
        }
        return onTouch(event)
    }

    /**
     * Called by [GLRootView.dispatchSaveInstanceState] to
     * store this GlView hierarchy's frozen state into the given container.
     *
     * @param container The SparseArray in which to save the view's state.
     */
    fun saveHierarchyState(container: SparseArray<Parcelable>) {
        dispatchSaveInstanceState(container)
    }

    protected open fun dispatchSaveInstanceState(container: SparseArray<Parcelable>) {
        if (id != NO_ID) {
            val state = onSaveInstanceState()
            if (state != null) {
                container.put(id, state)
            }
        }
        for (i in 0 until componentCount) {
            getComponent(i).dispatchSaveInstanceState(container)
        }
    }

    protected open fun onSaveInstanceState(): Parcelable? = null

    /**
     * Called by [GLRootView.dispatchRestoreInstanceState] to
     * restore this view hierarchy's frozen state from the given container.
     *
     * @param container The SparseArray which holds previously frozen states.
     */
    fun restoreHierarchyState(container: SparseArray<Parcelable>) {
        dispatchRestoreInstanceState(container)
    }

    protected open fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>) {
        if (id != NO_ID) {
            val state = container.get(id)
            if (state != null) {
                onRestoreInstanceState(state)
            }
        }
        for (i in 0 until componentCount) {
            getComponent(i).dispatchRestoreInstanceState(container)
        }
    }

    protected open fun onRestoreInstanceState(state: Parcelable?) {
        if (state != null) {
            throw IllegalArgumentException("Please override onRestoreInstanceState")
        }
    }

    fun setPaddings(paddingLeft: Int, paddingTop: Int, paddingRight: Int, paddingBottom: Int) {
        mPaddings.set(paddingLeft, paddingTop, paddingRight, paddingBottom)
    }

    fun setPaddingLeft(paddingLeft: Int) {
        mPaddings.left = paddingLeft
    }

    fun setPaddingTop(paddingTop: Int) {
        mPaddings.top = paddingTop
    }

    fun setPaddingRight(paddingRight: Int) {
        mPaddings.right = paddingRight
    }

    fun setPaddingBottom(paddingBottom: Int) {
        mPaddings.bottom = paddingBottom
    }

    val paddings: Rect
        get() = mPaddings

    // There is a little different between GLView.layout() and View.layout().
    // onMeasure() is not called in GLView.layout().
    // For the content view of GLRootView, GLView.measure() is called before GLView.layout().
    // For component, GLView.measure() in called in parent's GLView.onMeasure().
    // So it is OK.
    fun layout(left: Int, top: Int, right: Int, bottom: Int) {
        val sizeChanged = setBounds(left, top, right, bottom)
        val forceLayout = (viewFlags and FLAG_LAYOUT_REQUESTED) == FLAG_LAYOUT_REQUESTED
        if (sizeChanged || forceLayout) {
            notifyPositionInRoot()
            onLayout(sizeChanged, left, top, right, bottom)
        } else {
            dispatchNotifyPositionInRoot()
        }
        viewFlags = viewFlags and FLAG_LAYOUT_REQUESTED.inv()
    }

    fun dispatchNotifyPositionInRoot() {
        if (notifyPositionInRoot()) {
            for (i in 0 until componentCount) {
                getComponent(i).dispatchNotifyPositionInRoot()
            }
        }
    }

    fun notifyPositionInRoot(): Boolean {
        // Update position in root
        val position = positionInRoot
        val oldX = position[0]
        val oldY = position[1]
        val parent = mParent
        if (parent == null) {
            position[0] = 0
            position[1] = 0
        } else {
            parent.getPositionInRoot(position)
            // Apply offset in parent
            position[0] += mBounds.left
            position[1] += mBounds.top
        }
        val x = position[0]
        val y = position[1]
        if (x != oldX || y != oldY) {
            onPositionInRootChanged(x, y, oldX, oldY)
            return true
        } else {
            return false
        }
    }

    private fun setBounds(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val bounds = mBounds
        val oldW = bounds.right - bounds.left
        val oldH = bounds.bottom - bounds.top
        val newW = right - left
        val newH = bottom - top

        val sizeChanged = oldW != newW || oldH != newH
        bounds.set(left, top, right, bottom)

        if (sizeChanged) {
            onSizeChanged(newW, newH, oldW, oldH)
        }

        return sizeChanged
    }

    protected open fun onSizeChanged(newW: Int, newH: Int, oldW: Int, oldH: Int) {
    }

    protected open fun onPositionInRootChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
    }

    fun getPositionInRoot(position: IntArray) {
        AssertUtils.assertEquals("position should be 2 length int array", position.size, 2)
        position[0] = positionInRoot[0]
        position[1] = positionInRoot[1]
    }

    /**
     * Get the rect of the view clipped by root rect
     * @param rect the result
     */
    fun getValidRect(rect: Rect) {
        val root = this.root
        if (root == null) {
            rect.setEmpty()
            return
        }

        val x = positionInRoot[0]
        val y = positionInRoot[1]
        rect.set(x, y, x + width, y + height)
        if (!rect.intersect(0, 0, root.getWidth(), root.getHeight())) {
            rect.setEmpty()
            return
        }

        rect.offset(-x, -y)
    }

    fun measure(widthSpec: Int, heightSpec: Int) {
        val forceLayout = (viewFlags and FLAG_LAYOUT_REQUESTED) == FLAG_LAYOUT_REQUESTED
        val isExactly = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY &&
            MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
        val matchingSize = isExactly &&
            measuredWidth == MeasureSpec.getSize(widthSpec) &&
            measuredHeight == MeasureSpec.getSize(heightSpec)
        if (forceLayout || !matchingSize &&
            (widthSpec != lastWidthSpec || heightSpec != lastHeightSpec)
        ) {
            lastWidthSpec = widthSpec
            lastHeightSpec = heightSpec

            viewFlags = viewFlags and FLAG_SET_MEASURED_SIZE.inv()
            onMeasure(widthSpec, heightSpec)
            if ((viewFlags and FLAG_SET_MEASURED_SIZE) == 0) {
                throw IllegalStateException(
                    javaClass.name + " should call setMeasuredSize() in onMeasure()"
                )
            }
        }
    }

    protected open fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredSize(
            getDefaultSize(getSuggestedMinimumWidth(), widthSpec),
            getDefaultSize(getSuggestedMinimumHeight(), heightSpec)
        )
    }

    protected open fun getSuggestedMinimumHeight(): Int =
        minimumHeight + mPaddings.top + mPaddings.bottom

    protected open fun getSuggestedMinimumWidth(): Int =
        minimumWidth + mPaddings.left + mPaddings.right

    protected fun setMeasuredSize(width: Int, height: Int) {
        viewFlags = viewFlags or FLAG_SET_MEASURED_SIZE
        mMeasuredWidth = width
        mMeasuredHeight = height
    }

    val measuredWidth: Int
        get() = mMeasuredWidth

    val measuredHeight: Int
        get() = mMeasuredHeight

    protected open fun onLayout(
        changeSize: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
    }

    protected open fun checkLayoutParams(p: LayoutParams?): Boolean = p != null

    protected open fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    // Only called with params that failed checkLayoutParams; the base check
    // only rejects null, which addComponent never passes here.
    protected open fun generateLayoutParams(p: LayoutParams): LayoutParams = LayoutParams(p)

    /**
     * Gets the bounds of the given descendant that relative to this view.
     */
    fun getBoundsOf(descendant: GLView, out: Rect): Boolean {
        var xOffset = 0
        var yOffset = 0
        var view: GLView? = descendant
        while (view !== this) {
            if (view == null) return false
            val bounds = view.mBounds
            xOffset += bounds.left
            yOffset += bounds.top
            view = view.mParent
        }
        out.set(
            xOffset, yOffset, xOffset + descendant.width,
            yOffset + descendant.height
        )
        return true
    }

    protected open fun onVisibilityChanged(changedView: GLView, @Visibility visibility: Int) {
        for (i in 0 until componentCount) {
            getComponent(i).onVisibilityChanged(changedView, visibility)
        }
    }

    open fun onAttachToRoot(root: GLRoot) {
        this.root = root
        for (i in 0 until componentCount) {
            getComponent(i).onAttachToRoot(root)
        }
    }

    open fun onDetachFromRoot() {
        for (i in 0 until componentCount) {
            getComponent(i).onDetachFromRoot()
        }
        root = null
    }

    open fun onPause() {
        for (i in 0 until componentCount) {
            getComponent(i).onPause()
        }
    }

    open fun onResume() {
        for (i in 0 until componentCount) {
            getComponent(i).onResume()
        }
    }

    fun lockRendering() {
        root?.lockRenderThread()
    }

    fun unlockRendering() {
        root?.unlockRenderThread()
    }

    // This is for debugging only.
    // Dump the view hierarchy into log.
    @JvmName("dumpTree")
    internal fun dumpTree(prefix: String) {
        Log.d(TAG, prefix + javaClass.simpleName)
        for (i in 0 until componentCount) {
            getComponent(i).dumpTree("$prefix....")
        }
    }

    /**
     * A MeasureSpec encapsulates the layout requirements passed from parent to child.
     * Each MeasureSpec represents a requirement for either the width or the height.
     * A MeasureSpec is comprised of a size and a mode. There are three possible
     * modes:
     *
     * UNSPECIFIED: The parent has not imposed any constraint on the child. It can be
     * whatever size it wants.
     *
     * EXACTLY: The parent has determined an exact size for the child. The child is
     * going to be given those bounds regardless of how big it wants to be.
     *
     * AT_MOST: The child can be as large as it wants up to the specified size.
     *
     * MeasureSpecs are implemented as ints to reduce object allocation. This class
     * is provided to pack and unpack the &lt;size, mode&gt; tuple into the int.
     */
    object MeasureSpec {
        private const val MODE_SHIFT = 30
        private const val MODE_MASK = 0x3 shl MODE_SHIFT

        /**
         * Measure specification mode: The parent has not imposed any constraint
         * on the child. It can be whatever size it wants.
         */
        const val UNSPECIFIED = 0 shl MODE_SHIFT

        /**
         * Measure specification mode: The parent has determined an exact size
         * for the child. The child is going to be given those bounds regardless
         * of how big it wants to be.
         */
        const val EXACTLY = 1 shl MODE_SHIFT

        /**
         * Measure specification mode: The child can be as large as it wants up
         * to the specified size.
         */
        const val AT_MOST = 2 shl MODE_SHIFT

        /**
         * The max size allowed
         */
        const val MAX_SIZE = MODE_MASK.inv()

        /**
         * Creates a measure specification based on the supplied size and mode.
         *
         * The mode must always be one of [UNSPECIFIED], [EXACTLY] or [AT_MOST].
         *
         * @param size the size of the measure specification
         * @param mode the mode of the measure specification
         * @return the measure specification based on size and mode
         */
        @JvmStatic
        fun makeMeasureSpec(size: Int, mode: Int): Int =
            (size and MODE_MASK.inv()) or (mode and MODE_MASK)

        /**
         * Extracts the mode from the supplied measure specification.
         *
         * @param measureSpec the measure specification to extract the mode from
         * @return [UNSPECIFIED], [AT_MOST] or [EXACTLY]
         */
        @JvmStatic
        fun getMode(measureSpec: Int): Int = measureSpec and MODE_MASK

        /**
         * Extracts the size from the supplied measure specification.
         *
         * @param measureSpec the measure specification to extract the size from
         * @return the size in pixels defined in the supplied measure specification
         */
        @JvmStatic
        fun getSize(measureSpec: Int): Int = measureSpec and MODE_MASK.inv()

        /**
         * Returns a String representation of the specified measure
         * specification.
         *
         * @param measureSpec the measure specification to convert to a String
         * @return a String with the following format: "MeasureSpec: MODE SIZE"
         */
        @JvmStatic
        fun toString(measureSpec: Int): String {
            val mode = getMode(measureSpec)
            val size = getSize(measureSpec)

            val sb = StringBuilder("MeasureSpec: ")

            if (mode == UNSPECIFIED) {
                sb.append("UNSPECIFIED ")
            } else if (mode == EXACTLY) {
                sb.append("EXACTLY ")
            } else if (mode == AT_MOST) {
                sb.append("AT_MOST ")
            } else {
                sb.append(mode).append(" ")
            }

            sb.append(size)
            return sb.toString()
        }
    }

    /**
     * The LayoutParams look like [android.view.ViewGroup.LayoutParams],
     * and work like it.
     */
    open class LayoutParams {

        /**
         * Information about how wide the view wants to be. Can be one of the
         * constants [MATCH_PARENT] or [WRAP_CONTENT] or an exact size.
         */
        @JvmField
        var width: Int

        /**
         * Information about how tall the view wants to be. Can be one of the
         * constants [MATCH_PARENT] or [WRAP_CONTENT] or an exact size.
         */
        @JvmField
        var height: Int

        /**
         * Creates a new set of layout parameters with the specified width
         * and height.
         *
         * @param width the width, either [WRAP_CONTENT],
         * [MATCH_PARENT], or a fixed size in pixels
         * @param height the height, either [WRAP_CONTENT],
         * [MATCH_PARENT], or a fixed size in pixels
         */
        constructor(width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        /**
         * Copy constructor. Clones the width and height values of the source.
         *
         * @param source The layout params to copy from.
         */
        constructor(source: LayoutParams) {
            this.width = source.width
            this.height = source.height
        }

        companion object {
            /**
             * Special value for the height or width requested by a View.
             * MATCH_PARENT means that the view wants to be as big as its parent,
             * minus the parent's padding, if any. Introduced in API Level 8.
             */
            const val MATCH_PARENT = -1

            /**
             * Special value for the height or width requested by a View.
             * WRAP_CONTENT means that the view wants to be just large enough to fit
             * its own internal content, taking its own padding into account.
             */
            const val WRAP_CONTENT = -2
        }
    }

    /**
     * LayoutParams with gravity inside
     */
    open class GravityLayoutParams : LayoutParams {

        @JvmField
        var gravity = Gravity.NO_GRAVITY

        constructor(source: LayoutParams) : super(source) {
            if (source is GravityLayoutParams) {
                gravity = source.gravity
            }
        }

        constructor(width: Int, height: Int) : super(width, height)
    }

    companion object {
        private const val TAG = "GLView"

        private const val DEBUG_DRAW_BOUNDS = false

        const val VISIBLE = 0b00000000
        const val INVISIBLE = 0b00000001
        const val GONE = 0b00000010
        const val VISIBILITY_INVALID = 0b00000011

        private const val FLAG_INVISIBLE = 0b00000011
        private const val FLAG_SET_MEASURED_SIZE = 0b00000100
        private const val FLAG_LAYOUT_REQUESTED = 0b00001000

        /**
         * Used to mark a GlView that has no ID.
         */
        const val NO_ID = -1

        @JvmField
        protected val mDrawBoundsPaint: GLPaint = GLPaint()

        init {
            mDrawBoundsPaint.color = Color.RED
            mDrawBoundsPaint.lineWidth = 2f
        }

        /**
         * Utility to return a default begin. Uses the supplied number as left or top.
         *
         * @param size the parent size
         * @param specSize the child size
         * @param paddingBeign the padding begin
         * @param paddingFinish the padding finish
         * @param position the [Gravity.POSITION_BEGIN] or [Gravity.POSITION_CENTER] or
         * [Gravity.POSITION_FINISH]
         * @return the begin size
         */
        @JvmStatic
        fun getDefaultBegin(
            size: Int,
            specSize: Int,
            paddingBeign: Int,
            paddingFinish: Int,
            @Gravity.PositionMode position: Int
        ): Int {
            if (position == Gravity.POSITION_FINISH) {
                return size - paddingFinish - specSize
            } else if (position == Gravity.POSITION_CENTER) {
                return ((size - paddingBeign - paddingFinish) / 2) - (specSize / 2) + paddingBeign
            } else {
                return paddingBeign
            }
        }

        /**
         * Utility to return a default size. Uses the supplied size if the
         * MeasureSpec imposed no constraints. Will get larger if allowed
         * by the MeasureSpec.
         *
         * It works differently from [android.view.View.getDefaultSize].
         * For [MeasureSpec.AT_MOST], size is suggestion size
         * if it is small then spec size or it is not zero,
         * otherwise spec size.
         *
         * @param size Default size for this view
         * @param measureSpec Constraints imposed by the parent
         * @return The size this view should be.
         */
        @JvmStatic
        fun getDefaultSize(size: Int, measureSpec: Int): Int {
            var result = size
            val specMode = MeasureSpec.getMode(measureSpec)
            val specSize = MeasureSpec.getSize(measureSpec)

            when (specMode) {
                MeasureSpec.UNSPECIFIED -> result = size
                MeasureSpec.EXACTLY -> result = specSize
                MeasureSpec.AT_MOST -> return if (size == 0) specSize else min(size, specSize)
            }
            return result
        }

        /**
         * Utility to return a max size. Uses the supplied size if the
         * MeasureSpec imposed no constraints. Will get larger if allowed
         * by the MeasureSpec.
         *
         * It works the same as [android.view.View.getDefaultSize].
         *
         * @param size Default size for this view
         * @param measureSpec Constraints imposed by the parent
         * @return The size this view should be.
         */
        @JvmStatic
        fun getMaxSize(size: Int, measureSpec: Int): Int {
            var result = size
            val specMode = MeasureSpec.getMode(measureSpec)
            val specSize = MeasureSpec.getSize(measureSpec)

            when (specMode) {
                MeasureSpec.UNSPECIFIED -> result = size
                MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> result = specSize
            }
            return result
        }

        @JvmStatic
        fun getComponentSpec(spec: Int, childSize: Int): Int {
            val specMode = MeasureSpec.getMode(spec)
            val specSize = MeasureSpec.getSize(spec)

            val size = max(0, specSize)

            var resultSize = 0
            var resultMode = 0

            when (specMode) {
                // Parent has imposed an exact size on us
                MeasureSpec.EXACTLY -> if (childSize >= 0) {
                    resultSize = childSize
                    resultMode = MeasureSpec.EXACTLY
                } else if (childSize == LayoutParams.MATCH_PARENT) {
                    // Child wants to be our size. So be it.
                    resultSize = size
                    resultMode = MeasureSpec.EXACTLY
                } else if (childSize == LayoutParams.WRAP_CONTENT) {
                    // Child wants to determine its own size. It can't be
                    // bigger than us.
                    resultSize = size
                    resultMode = MeasureSpec.AT_MOST
                }

                // Parent has imposed a maximum size on us
                MeasureSpec.AT_MOST -> if (childSize >= 0) {
                    // Child wants a specific size... so be it
                    resultSize = childSize
                    resultMode = MeasureSpec.EXACTLY
                } else if (childSize == LayoutParams.MATCH_PARENT) {
                    // Child wants to be our size, but our size is not fixed.
                    // Constrain child to not be bigger than us.
                    resultSize = size
                    resultMode = MeasureSpec.AT_MOST
                } else if (childSize == LayoutParams.WRAP_CONTENT) {
                    // Child wants to determine its own size. It can't be
                    // bigger than us.
                    resultSize = size
                    resultMode = MeasureSpec.AT_MOST
                }

                // Parent asked to see how big we want to be
                MeasureSpec.UNSPECIFIED -> if (childSize >= 0) {
                    // Child wants a specific size... let him have it
                    resultSize = childSize
                    resultMode = MeasureSpec.EXACTLY
                } else if (childSize == LayoutParams.MATCH_PARENT) {
                    // Child wants to be our size... find out how big it should
                    // be
                    resultSize = 0
                    resultMode = MeasureSpec.UNSPECIFIED
                } else if (childSize == LayoutParams.WRAP_CONTENT) {
                    // Child wants to determine its own size.... find out how
                    // big it should be
                    resultSize = 0
                    resultMode = MeasureSpec.UNSPECIFIED
                }
            }
            return MeasureSpec.makeMeasureSpec(resultSize, resultMode)
        }

        @JvmStatic
        fun measureComponent(component: GLView, widthSpec: Int, heightSpec: Int) {
            val lp = component.layoutParams!!
            val componentWidthSpec = getComponentSpec(widthSpec, lp.width)
            val componentHeightSpec = getComponentSpec(heightSpec, lp.height)
            component.measure(componentWidthSpec, componentHeightSpec)
        }

        @JvmStatic
        fun measureAllComponents(parent: GLView, widthSpec: Int, heightSpec: Int) {
            for (i in 0 until parent.componentCount) {
                val component = parent.getComponent(i)
                if (component.visibility == GONE) {
                    continue
                }
                measureComponent(component, widthSpec, heightSpec)
            }
        }
    }
}
