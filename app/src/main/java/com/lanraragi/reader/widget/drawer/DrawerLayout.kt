/*
 * Copyright 2015 Hippo Seven
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

package com.lanraragi.reader.widget.drawer

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.GravityCompat
import androidx.customview.widget.ViewDragHelper
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import kotlin.math.abs

/**
 * Left/right drawers over a content view. Unlike AndroidX DrawerLayout a
 * drawer opens from a horizontal drag anywhere on the content (a
 * [GestureBlocker] can exempt areas such as horizontal scrollers) and
 * follows the finger; the drawers move, the content stays put. Children
 * that implement [DrawerLayoutChild] get the window insets and say which
 * margins they want; the status and navigation bar areas are painted with
 * [setStatusBarColor] / [setNavigationBarColor].
 *
 * Ported to Kotlin from seven332's DrawerLayout library (commit
 * ea2bb388f0), behaviour unchanged.
 */
@SuppressLint("RtlHardcoded")
open class DrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr), ValueAnimator.AnimatorUpdateListener, Animator.AnimatorListener {

    /** Listener for monitoring events about drawers. */
    interface DrawerListener {
        /** A drawer's position changed; [percent] is its open fraction, 0..1. */
        fun onDrawerSlide(drawerView: View, percent: Float)

        /** A drawer settled completely open. */
        fun onDrawerOpened(drawerView: View)

        /** A drawer settled completely closed. */
        fun onDrawerClosed(drawerView: View)

        /** A drawer moved between closed, sliding and open. */
        fun onDrawerStateChanged(drawerView: View, newState: Int)
    }

    fun interface GestureBlocker {
        /** `true` if the gesture should be blocked; only checked at its start. */
        fun shouldBlockGesture(ev: MotionEvent): Boolean
    }

    private val minDrawerMargin = (MIN_DRAWER_MARGIN * context.resources.displayMetrics.density + 0.5f).toInt()
    private var inLayout = false

    private var contentView: View? = null
    private var leftDrawer: View? = null
    private var rightDrawer: View? = null
    private lateinit var shadow: ShadowView

    private val dragHelper: ViewDragHelper = ViewDragHelper.create(this, 0.5f, DragHelperCallback())
    private var initialMotionX = 0f
    private var initialMotionY = 0f

    private var leftOpened = false
    private var rightOpened = false
    private var leftState = STATE_CLOSED
    private var rightState = STATE_CLOSED
    private var leftPercent = 0.0f
    private var rightPercent = 0.0f
    private var leftLockMode = LOCK_MODE_UNLOCKED
    private var rightLockMode = LOCK_MODE_UNLOCKED

    private val animator = ValueAnimator()
    private var targetView: View? = null
    private var startLeft = 0
    private var endLeft = 0
    private var toOpen = false
    private var cancelAnimation = false
    private var openTask: View? = null

    private var listener: DrawerListener? = null

    private var intercepted = false
    private var canIntercept = false

    private var windowPaddingTop = 0
    private var windowPaddingBottom = 0

    private var statusBarColor = Color.BLACK
    private val statusBarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { color = statusBarColor }
    private var navigationBarColor = Color.BLACK
    private val navigationBarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { color = navigationBarColor }

    private var gestureBlocker: GestureBlocker? = null

    init {
        animator.setFloatValues(0.0f, 1.0f)
        animator.addUpdateListener(this)
        animator.addListener(this)
        animator.interpolator = DRAWER_INTERPOLATOR
        setWillNotDraw(false)
        if (fitsSystemWindows) {
            // Lay out in the window insets.
            @Suppress("DEPRECATION")
            systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_STABLE or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    fun setDrawerListener(listener: DrawerListener?) {
        this.listener = listener
    }

    fun setGestureBlocker(blocker: GestureBlocker?) {
        gestureBlocker = blocker
    }

    /** Animates every open drawer closed. */
    fun closeDrawers() {
        closeDrawer(leftDrawer)
        closeDrawer(rightDrawer)
    }

    /** Animates [drawerView] open (closing the other side first). */
    fun openDrawer(drawerView: View?) {
        if (drawerView == null || (drawerView !== leftDrawer && drawerView !== rightDrawer)) return
        val isLeft = drawerView === leftDrawer
        when (if (isLeft) leftState else rightState) {
            STATE_CLOSED -> openClosedDrawer(isLeft)
            STATE_SLIDING -> if (animator.isRunning && !toOpen) {
                cancelAnimation()
                startAnimation(isLeft, isOpen = true, staticDuration = false)
            }
            // STATE_SLIDING without an animation: the finger is on the screen.
            // STATE_OPEN: already open.
        }
    }

    private fun openClosedDrawer(isLeft: Boolean) {
        val drawer = if (isLeft) leftDrawer else rightDrawer
        when (if (isLeft) rightState else leftState) {
            STATE_CLOSED -> startAnimation(isLeft, isOpen = true, staticDuration = false)
            STATE_SLIDING -> if (animator.isRunning) {
                if (toOpen) {
                    cancelAnimation()
                    openTask = drawer
                    startAnimation(!isLeft, isOpen = false, staticDuration = false)
                } else {
                    openTask = drawer
                }
            }
            STATE_OPEN -> {
                // Close the other side first.
                openTask = drawer
                startAnimation(!isLeft, isOpen = false, staticDuration = false)
            }
        }
    }

    fun openDrawer(gravity: Int) {
        if (gravity == Gravity.LEFT) {
            openDrawer(leftDrawer)
        } else if (gravity == Gravity.RIGHT) {
            openDrawer(rightDrawer)
        }
    }

    /** Animates [drawerView] closed. */
    fun closeDrawer(drawerView: View?) {
        if (drawerView == null || (drawerView !== leftDrawer && drawerView !== rightDrawer)) return
        val isLeft = drawerView === leftDrawer
        when (if (isLeft) leftState else rightState) {
            STATE_SLIDING -> if (animator.isRunning && toOpen) {
                cancelAnimation()
                startAnimation(isLeft, isOpen = false, staticDuration = false)
            }
            STATE_OPEN -> startAnimation(isLeft, isOpen = false, staticDuration = false)
            // STATE_CLOSED: already closed.
        }
    }

    fun closeDrawer(gravity: Int) {
        if (gravity == Gravity.LEFT) {
            closeDrawer(leftDrawer)
        } else if (gravity == Gravity.RIGHT) {
            closeDrawer(rightDrawer)
        }
    }

    fun isDrawerOpen(drawer: View?): Boolean = when {
        drawer === leftDrawer -> leftOpened
        drawer === rightDrawer -> rightOpened
        else -> throw IllegalArgumentException("The view is not drawer")
    }

    fun isDrawerOpen(gravity: Int): Boolean = when (gravity) {
        Gravity.LEFT -> isDrawerOpen(leftDrawer)
        Gravity.RIGHT -> isDrawerOpen(rightDrawer)
        else -> throw IllegalArgumentException("gravity must be Gravity.LEFT or Gravity.RIGHT")
    }

    fun setDrawerShadow(@DrawableRes resId: Int, gravity: Int) {
        setDrawerShadow(ContextCompat.getDrawable(context, resId), gravity)
    }

    fun setDrawerShadow(shadowDrawable: Drawable?, gravity: Int) {
        when (gravity) {
            Gravity.LEFT -> shadow.shadowLeft = shadowDrawable
            Gravity.RIGHT -> shadow.shadowRight = shadowDrawable
            else -> error("setDrawerShadow only support Gravity.LEFT and Gravity.RIGHT")
        }
        invalidate()
    }

    fun setDrawerLockMode(lockMode: Int, gravity: Int) {
        when (gravity) {
            Gravity.LEFT -> setDrawerLockMode(lockMode, leftDrawer)
            Gravity.RIGHT -> setDrawerLockMode(lockMode, rightDrawer)
            else -> throw IllegalArgumentException("gravity must be Gravity.LEFT or Gravity.RIGHT")
        }
    }

    fun setDrawerLockMode(lockMode: Int, drawer: View?) {
        require(drawer === leftDrawer || drawer === rightDrawer) { "The view is not drawer" }
        val oldLockMode: Int
        val otherSideLockMode: Int
        if (drawer === leftDrawer) {
            oldLockMode = leftLockMode
            otherSideLockMode = rightLockMode
            leftLockMode = lockMode
        } else {
            oldLockMode = rightLockMode
            otherSideLockMode = leftLockMode
            rightLockMode = lockMode
        }
        if (oldLockMode == lockMode) return
        require(!(otherSideLockMode == LOCK_MODE_LOCKED_OPEN && lockMode == LOCK_MODE_LOCKED_OPEN)) {
            "Only on side could be LOCK_MODE_LOCKED_OPEN"
        }
        when (lockMode) {
            LOCK_MODE_LOCKED_OPEN -> openDrawer(drawer)
            LOCK_MODE_LOCKED_CLOSED -> closeDrawer(drawer)
        }
    }

    fun getDrawerLockMode(gravity: Int): Int = when (gravity) {
        Gravity.LEFT -> getDrawerLockMode(leftDrawer)
        Gravity.RIGHT -> getDrawerLockMode(rightDrawer)
        else -> throw IllegalArgumentException("gravity must be Gravity.LEFT or Gravity.RIGHT")
    }

    fun getDrawerLockMode(drawer: View?): Int = when {
        drawer === leftDrawer -> leftLockMode
        drawer === rightDrawer -> rightLockMode
        else -> throw IllegalArgumentException("The view is not drawer")
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val absGravity = GravityCompat.getAbsoluteGravity(
                (child.layoutParams as LayoutParams).gravity, child.layoutDirection
            )
            when {
                absGravity == Gravity.NO_GRAVITY -> {
                    check(contentView == null) { "There is more than one content view" }
                    contentView = child
                }
                absGravity and Gravity.LEFT == Gravity.LEFT -> {
                    check(leftDrawer == null) { "There is more than one left menu" }
                    leftDrawer = child
                }
                absGravity and Gravity.RIGHT == Gravity.RIGHT -> {
                    check(rightDrawer == null) { "There is more than one right menu" }
                    rightDrawer = child
                }
                else -> error(
                    "Child $child at index $i does not have a valid layout_gravity - must be Gravity.LEFT, " +
                        "Gravity.RIGHT or Gravity.NO_GRAVITY"
                )
            }
        }
        checkNotNull(contentView) { "There is no content view" }
        // Material is solid: input events cannot pass through a drawer.
        leftDrawer?.isClickable = true
        rightDrawer?.isClickable = true

        shadow = ShadowView(context)
        addView(shadow, 1)
    }

    private fun additionalMargins(child: View): Pair<Int, Int> =
        (child as? DrawerLayoutChild)?.let { it.getAdditionalTopMargin() to it.getAdditionalBottomMargin() } ?: (0 to 0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        require(
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY &&
                MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        ) { "DrawerLayout must be measured with MeasureSpec.EXACTLY." }
        setMeasuredDimension(widthSize, heightSize)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val (top, bottom) = additionalMargins(child)
            val verticalMargin = top + bottom
            val lp = child.layoutParams as LayoutParams
            when {
                child === contentView -> child.measure(
                    MeasureSpec.makeMeasureSpec(widthSize - lp.leftMargin - lp.rightMargin, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                        heightSize - lp.topMargin - lp.bottomMargin - verticalMargin, MeasureSpec.EXACTLY
                    )
                )
                child === leftDrawer || child === rightDrawer -> child.measure(
                    getChildMeasureSpec(
                        widthMeasureSpec, lp.leftMargin + lp.rightMargin + minDrawerMargin, minOf(widthSize, lp.width)
                    ),
                    getChildMeasureSpec(heightMeasureSpec, lp.topMargin + lp.bottomMargin + verticalMargin, lp.height)
                )
                child === shadow -> child.measure(
                    MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
                )
                else -> error("Don't call addView")
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        inLayout = true
        val width = r - l
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val (additionalTop, additionalBottom) = additionalMargins(child)
            when {
                child === contentView -> child.layout(
                    lp.leftMargin, lp.topMargin + additionalTop,
                    lp.leftMargin + child.measuredWidth, lp.topMargin + additionalTop + child.measuredHeight
                )
                child === shadow -> child.layout(0, 0, child.measuredWidth, child.measuredHeight)
                // A drawer, or onMeasure would have thrown.
                else -> layoutDrawer(child, lp, width, b - t, additionalTop, additionalBottom)
            }
        }
        inLayout = false
    }

    private fun layoutDrawer(child: View, lp: LayoutParams, width: Int, height: Int, additionalTop: Int, additionalBottom: Int) {
        val childWidth = child.measuredWidth
        val childHeight = child.measuredHeight
        val percent: Float
        val childLeft: Int
        if (child === leftDrawer) {
            percent = leftPercent
            childLeft = -childWidth + (childWidth * percent).toInt()
        } else {
            percent = rightPercent
            childLeft = width - (childWidth * percent).toInt()
        }
        when (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> child.layout(
                childLeft, height - lp.bottomMargin - additionalBottom - childHeight,
                childLeft + childWidth, height - lp.bottomMargin - additionalBottom
            )
            Gravity.CENTER_VERTICAL -> {
                var childTop = (height - childHeight - additionalTop - additionalBottom - lp.topMargin - lp.bottomMargin) / 2 +
                    additionalTop
                // Offset for margins. If things don't fit right because of
                // bad measurement before, oh well.
                if (childTop < lp.topMargin + additionalTop) {
                    childTop = lp.topMargin + additionalTop
                } else if (childTop + childHeight > height - additionalBottom - lp.bottomMargin) {
                    childTop = height - additionalBottom - lp.bottomMargin - childHeight
                }
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
            }
            else -> child.layout(
                childLeft, lp.topMargin + additionalTop, childLeft + childWidth, lp.topMargin + additionalTop + childHeight
            )
        }
        val newVisibility = if (percent > 0) VISIBLE else INVISIBLE
        if (child.visibility != newVisibility) child.visibility = newVisibility
    }

    override fun requestLayout() {
        if (!inLayout) super.requestLayout()
    }

    private fun isDrawersTouchable(): Boolean =
        (leftDrawer != null && leftLockMode == LOCK_MODE_UNLOCKED) ||
            (rightDrawer != null && rightLockMode == LOCK_MODE_UNLOCKED)

    private fun getActualDxLeft(drawer: View, dx: Int): Int {
        val leftWidth = drawer.width
        val oldLeft = drawer.left
        val newLeft = (oldLeft + dx).coerceIn(-leftWidth, 0)
        val newVisible = if (newLeft == -leftWidth) INVISIBLE else VISIBLE
        if (drawer.visibility != newVisible) drawer.visibility = newVisible
        updateDrawerSlide(drawer, (newLeft + leftWidth) / leftWidth.toFloat())
        return newLeft - oldLeft
    }

    private fun getActualDxRight(drawer: View, dx: Int): Int {
        val width = width
        val rightWidth = drawer.width
        val oldLeft = drawer.left
        val newLeft = (oldLeft + dx).coerceIn(width - rightWidth, width)
        val newVisible = if (newLeft == width) INVISIBLE else VISIBLE
        if (drawer.visibility != newVisible) drawer.visibility = newVisible
        updateDrawerSlide(drawer, (width - newLeft) / rightWidth.toFloat())
        return newLeft - oldLeft
    }

    private fun slideLeftDrawer(dx: Int) {
        leftDrawer?.let { it.offsetLeftAndRight(getActualDxLeft(it, dx)) }
    }

    private fun slideRightDrawer(dx: Int) {
        rightDrawer?.let { it.offsetLeftAndRight(getActualDxRight(it, dx)) }
    }

    override fun onAnimationUpdate(animation: ValueAnimator) {
        val target = targetView ?: return
        val value = animation.animatedValue as Float
        val newLeft = lerp(startLeft, endLeft, value)
        if (target === leftDrawer) slideLeftDrawer(newLeft - target.left) else slideRightDrawer(newLeft - target.left)
    }

    override fun onAnimationStart(animation: Animator) {
        targetView?.let { updateDrawerState(it, STATE_SLIDING) }
    }

    override fun onAnimationEnd(animation: Animator) {
        val target = targetView
        if (!cancelAnimation && target != null) {
            settle(target)
            val task = openTask?.takeIf { it === leftDrawer || it === rightDrawer }
            if (task != null && !toOpen) {
                // Starting the animation directly here would skip its
                // onAnimationStart / onAnimationEnd.
                val isLeft = task === leftDrawer
                handler.post { startAnimation(isLeft, isOpen = true, staticDuration = false) }
            }
        }
        cancelAnimation = false
        openTask = null
    }

    override fun onAnimationCancel(animation: Animator) = Unit

    override fun onAnimationRepeat(animation: Animator) = Unit

    /** Reports [target] fully at the end [toOpen] points to. */
    private fun settle(target: View) {
        updateDrawerSlide(target, if (toOpen) 1.0f else 0.0f)
        updateDrawerState(target, if (toOpen) STATE_OPEN else STATE_CLOSED)
        if (toOpen) dispatchOnDrawerOpened(target) else dispatchOnDrawerClosed(target)
    }

    private fun startAnimation(isLeft: Boolean, isOpen: Boolean, staticDuration: Boolean) {
        val target = (if (isLeft) leftDrawer else rightDrawer) ?: return
        toOpen = isOpen
        targetView = target
        startLeft = target.left
        val total = target.width
        endLeft = when {
            isLeft -> if (toOpen) 0 else -total
            else -> if (toOpen) width - total else width
        }
        if (startLeft == endLeft) {
            // No need to animate.
            settle(target)
            return
        }
        animator.duration = (if (staticDuration) ANIMATE_TIME else ANIMATE_TIME * abs(startLeft - endLeft) / total).toLong()
        animator.start()
    }

    private fun shouldCloseDrawers(x: Float, y: Float): Boolean {
        val drawer = when {
            leftPercent > 0.0f -> leftDrawer
            rightPercent > 0.0f -> rightDrawer
            else -> null
        } ?: return false
        val yInt = y.toInt()
        return !isViewUnder(drawer, x.toInt(), yInt) && yInt >= drawer.top && yInt < drawer.bottom
    }

    /** `true` if any drawer is visible. */
    val isDrawersVisible: Boolean
        get() = leftPercent > 0 || rightPercent > 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        if (!isDrawersTouchable()) return false
        if (!shouldCloseDrawers(x, y) && gestureBlocker?.shouldBlockGesture(ev) == true) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                intercepted = shouldCloseDrawers(x, y)
                canIntercept = true
                initialMotionX = x
                initialMotionY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val adx = abs(x - initialMotionX)
                val ady = abs(y - initialMotionY)
                val slop = dragHelper.touchSlop
                if (ady > slop && ady > adx) canIntercept = false
                if (canIntercept && adx > slop && adx > ady) intercepted = true
                if (shouldCloseDrawers(x, y)) intercepted = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragHelper.cancel()
                if (shouldCloseDrawers(x, y)) closeDrawers()
            }
        }
        feedDragHelper { dragHelper.shouldInterceptTouchEvent(ev) }
        return intercepted
    }

    private fun cancelAnimation() {
        if (animator.isRunning) {
            openTask = null
            cancelAnimation = true
            animator.cancel()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        cancelAnimation()
        feedDragHelper { dragHelper.processTouchEvent(ev) }

        val action = ev.actionMasked
        val x = ev.x
        val y = ev.y
        if (action == MotionEvent.ACTION_DOWN) {
            if (!shouldCloseDrawers(x, y) && gestureBlocker?.shouldBlockGesture(ev) == true) return false
            intercepted = true
        }
        if (!intercepted || !isDrawersTouchable()) return false

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                initialMotionX = x
                initialMotionY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when {
                leftState == STATE_SLIDING -> settleAfterDrag(isLeft = true, leftOpened, leftPercent)
                rightState == STATE_SLIDING -> settleAfterDrag(isLeft = false, rightOpened, rightPercent)
                shouldCloseDrawers(x, y) -> closeDrawers()
            }
        }
        return true
    }

    /** At the end of a drag: an opened drawer needs to lose 10 %, a closed one to gain 10 %, to switch. */
    private fun settleAfterDrag(isLeft: Boolean, opened: Boolean, percent: Float) {
        when {
            opened && percent < CLOSE_SENSITIVITY -> startAnimation(isLeft, isOpen = false, staticDuration = false)
            !opened && percent < OPEN_SENSITIVITY -> startAnimation(isLeft, isOpen = false, staticDuration = true)
            opened -> startAnimation(isLeft, isOpen = true, staticDuration = true)
            else -> startAnimation(isLeft, isOpen = true, staticDuration = false)
        }
    }

    /** ViewDragHelper throws on inconsistent pointer streams; the library ignored that. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private inline fun feedDragHelper(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Throwable) {
            // Ignore, as the library did.
        }
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int = drawingPosition

    private fun updateDrawerSlide(drawerView: View, percent: Float) {
        val update = when {
            drawerView === leftDrawer -> (leftPercent != percent).also { leftPercent = percent }
            drawerView === rightDrawer -> (rightPercent != percent).also { rightPercent = percent }
            else -> false
        }
        if (update) {
            shadow.setPercent(percent)
            listener?.onDrawerSlide(drawerView, percent)
        }
    }

    private fun updateDrawerState(drawerView: View, state: Int) {
        val update = when {
            drawerView === leftDrawer -> (leftState != state).also { leftState = state }
            drawerView === rightDrawer -> (rightState != state).also { rightState = state }
            else -> false
        }
        if (update) listener?.onDrawerStateChanged(drawerView, state)
    }

    private fun dispatchOnDrawerClosed(drawerView: View) {
        val update = when {
            drawerView === leftDrawer -> leftOpened.also { leftOpened = false }
            drawerView === rightDrawer -> rightOpened.also { rightOpened = false }
            else -> false
        }
        if (update) listener?.onDrawerClosed(drawerView)
    }

    private fun dispatchOnDrawerOpened(drawerView: View) {
        val update = when {
            drawerView === leftDrawer -> (!leftOpened).also { leftOpened = true }
            drawerView === rightDrawer -> (!rightOpened).also { rightOpened = true }
            else -> false
        }
        if (update) listener?.onDrawerOpened(drawerView)
    }

    fun setStatusBarColor(statusBarColor: Int) {
        if (this.statusBarColor != statusBarColor) {
            this.statusBarColor = statusBarColor
            statusBarPaint.color = statusBarColor
            if (windowPaddingTop != 0 && width != 0) invalidate(0, 0, width, windowPaddingTop)
        }
    }

    fun getStatusBarColor(): Int = statusBarColor

    fun setNavigationBarColor(navigationBarColor: Int) {
        if (this.navigationBarColor != navigationBarColor) {
            this.navigationBarColor = navigationBarColor
            navigationBarPaint.color = navigationBarColor
            if (windowPaddingBottom != 0 && width != 0) invalidate(0, height - windowPaddingBottom, width, height)
        }
    }

    fun getNavigationBarColor(): Int = navigationBarColor

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val hidden = when {
            child === leftDrawer -> leftPercent == 0.0f
            child === rightDrawer -> rightPercent == 0.0f
            child === shadow -> leftPercent == 0.0f && rightPercent == 0.0f
            else -> false
        }
        // A hidden drawer or an idle shadow needs no drawing.
        return hidden || super.drawChild(canvas, child, drawingTime)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (windowPaddingTop != 0 && statusBarColor != Color.TRANSPARENT) {
            canvas.drawRect(0f, 0f, width.toFloat(), windowPaddingTop.toFloat(), statusBarPaint)
        }
        if (windowPaddingBottom != 0 && navigationBarColor != Color.TRANSPARENT) {
            canvas.drawRect(0f, (height - windowPaddingBottom).toFloat(), width.toFloat(), height.toFloat(), navigationBarPaint)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun fitSystemWindows(insets: Rect): Boolean {
        windowPaddingTop = insets.top
        windowPaddingBottom = insets.bottom
        insets.top = 0
        insets.bottom = 0
        for (i in 0 until childCount) {
            (getChildAt(i) as? DrawerLayoutChild)?.onGetWindowPadding(windowPaddingTop, windowPaddingBottom)
        }
        return super.fitSystemWindows(insets)
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(KEY_SUPER, super.onSaveInstanceState())
        if (isDrawerOpen(Gravity.LEFT)) {
            putInt(KEY_OPENED_DRAWER, Gravity.LEFT)
        } else if (isDrawerOpen(Gravity.RIGHT)) {
            putInt(KEY_OPENED_DRAWER, Gravity.RIGHT)
        }
        putInt(KEY_LEFT_LOCK_MODE, leftLockMode)
        putInt(KEY_RIGHT_LOCK_MODE, rightLockMode)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val saved = state as Bundle
        setDrawerLockMode(saved.getInt(KEY_LEFT_LOCK_MODE, LOCK_MODE_UNLOCKED), Gravity.LEFT)
        setDrawerLockMode(saved.getInt(KEY_RIGHT_LOCK_MODE, LOCK_MODE_UNLOCKED), Gravity.RIGHT)
        openDrawer(saved.getInt(KEY_OPENED_DRAWER, Gravity.NO_GRAVITY))
        super.onRestoreInstanceState(BundleCompat.getParcelable(saved, KEY_SUPER, Parcelable::class.java))
    }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams = when (p) {
        is LayoutParams -> LayoutParams(p)
        is MarginLayoutParams -> LayoutParams(p)
        else -> LayoutParams(p)
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams && super.checkLayoutParams(p)

    override fun generateLayoutParams(attrs: AttributeSet): ViewGroup.LayoutParams = LayoutParams(context, attrs)

    class LayoutParams : MarginLayoutParams {

        @JvmField
        var gravity = Gravity.NO_GRAVITY

        constructor(c: Context, attrs: AttributeSet?) : super(c, attrs) {
            val a = c.obtainStyledAttributes(attrs, LAYOUT_ATTRS)
            gravity = a.getInt(0, Gravity.NO_GRAVITY)
            a.recycle()
        }

        constructor(width: Int, height: Int) : super(width, height)

        constructor(width: Int, height: Int, gravity: Int) : super(width, height) {
            this.gravity = gravity
        }

        constructor(source: LayoutParams) : super(source) {
            gravity = source.gravity
        }

        constructor(source: ViewGroup.LayoutParams) : super(source)

        constructor(source: MarginLayoutParams) : super(source)
    }

    private inner class DragHelperCallback : ViewDragHelper.Callback() {

        override fun tryCaptureView(child: View, pointerId: Int): Boolean = true

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int = child.top

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            if (!intercepted) return child.left
            val leftView = leftDrawer
            val rightView = rightDrawer
            when {
                child === contentView || child === shadow -> dragFromContent(dx)
                child === leftView -> {
                    updateDrawerState(leftView, STATE_SLIDING)
                    return child.left + getActualDxLeft(leftView, dx)
                }
                child === rightView -> {
                    updateDrawerState(rightView, STATE_SLIDING)
                    return child.left + getActualDxRight(rightView, dx)
                }
            }
            return child.left
        }

        /** A drag on the content opens a closed drawer, or moves the one already out. */
        private fun dragFromContent(dx: Int) {
            val left = leftDrawer
            val right = rightDrawer
            if (leftState == STATE_CLOSED && rightState == STATE_CLOSED) {
                if (dx > 0 && left != null && leftLockMode == LOCK_MODE_UNLOCKED) {
                    slideLeftDrawer(dx)
                    updateDrawerState(left, STATE_SLIDING)
                } else if (dx < 0 && right != null && rightLockMode == LOCK_MODE_UNLOCKED) {
                    slideRightDrawer(dx)
                    updateDrawerState(right, STATE_SLIDING)
                }
            } else if (leftState != STATE_CLOSED && left != null) {
                slideLeftDrawer(dx)
                updateDrawerState(left, STATE_SLIDING)
            } else if (rightState != STATE_CLOSED && right != null) {
                slideRightDrawer(dx)
                updateDrawerState(right, STATE_SLIDING)
            }
        }

        override fun getViewHorizontalDragRange(child: View): Int = Int.MAX_VALUE
    }

    /** The dim scrim beside an open drawer, plus its optional edge shadow. */
    private inner class ShadowView(context: Context) : View(context) {

        private var percent = 0f
        var shadowLeft: Drawable? = null
        var shadowRight: Drawable? = null

        fun setPercent(percent: Float) {
            this.percent = percent
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val width = width
            val left = leftDrawer
            val right = rightDrawer
            val drawer: View
            val dbLeft: Int
            val dbRight: Int
            when {
                left != null && left.right > 0 -> {
                    drawer = left
                    dbLeft = left.right
                    dbRight = width
                }
                right != null && right.left < width -> {
                    drawer = right
                    dbLeft = 0
                    dbRight = right.left
                }
                // No dim background or shadow to draw.
                else -> return
            }
            val dbTop = drawer.top
            val dbBottom = drawer.bottom

            // Dim background.
            val saved = c.save()
            c.clipRect(dbLeft, dbTop, dbRight, dbBottom)
            c.drawARGB(lerp(SCRIM_FROM, SCRIM_TO, percent), 0, 0, 0)
            c.restoreToCount(saved)

            // Edge shadow.
            if (drawer === left) {
                shadowLeft?.let {
                    it.setBounds(left.right, dbTop, left.right + it.intrinsicWidth, dbBottom)
                    it.alpha = (0xff * leftPercent).toInt()
                    it.draw(c)
                }
            } else {
                shadowRight?.let {
                    it.setBounds(drawer.left - it.intrinsicWidth, dbTop, drawer.left, dbBottom)
                    it.alpha = (0xff * rightPercent).toInt()
                    it.draw(c)
                }
            }
        }
    }

    companion object {
        private const val KEY_SUPER = "super"
        private const val KEY_OPENED_DRAWER = "opened_drawer"
        private const val KEY_LEFT_LOCK_MODE = "left_lock_mode"
        private const val KEY_RIGHT_LOCK_MODE = "right_lock_mode"

        private val LAYOUT_ATTRS = intArrayOf(android.R.attr.layout_gravity)

        private const val ANIMATE_TIME = 300

        const val STATE_CLOSED = 0
        const val STATE_SLIDING = 1
        const val STATE_OPEN = 2

        /** The drawer is unlocked. */
        const val LOCK_MODE_UNLOCKED = 0

        /** Locked closed: the user cannot open it, the app still can. */
        const val LOCK_MODE_LOCKED_CLOSED = 1

        /** Locked open: the user cannot close it, the app still can. */
        const val LOCK_MODE_LOCKED_OPEN = 2

        private const val OPEN_SENSITIVITY = 0.1f
        private const val CLOSE_SENSITIVITY = 0.9f

        private val DRAWER_INTERPOLATOR = LinearOutSlowInInterpolator()

        private const val MIN_DRAWER_MARGIN = 56

        private const val SCRIM_FROM = 0x0
        private const val SCRIM_TO = 0x99

        private fun lerp(start: Int, stop: Int, amount: Float): Int = start + ((stop - start) * amount).toInt()

        @JvmStatic
        fun isViewUnder(view: View?, x: Int, y: Int): Boolean {
            if (view == null) return false
            val translationX = view.translationX
            val translationY = view.translationY
            val inX = x >= view.left + translationX && x < view.right + translationX
            val inY = y >= view.top + translationY && y < view.bottom + translationY
            return inX && inY
        }
    }
}
