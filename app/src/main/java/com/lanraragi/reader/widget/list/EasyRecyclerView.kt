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

package com.lanraragi.reader.widget.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.util.AttributeSet
import android.util.StateSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.widget.Checkable
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * RecyclerView with item click / long-click listeners that draw a pressed
 * selector over the touched row (like AbsListView). Rows are drawn checked
 * (Checkable) or activated from [checkedProvider], a selection the screen
 * owns; this view keeps no choice state of its own.
 *
 * Ported to Kotlin from seven332's EasyRecyclerView library (0.1.1); its
 * choice modes are gone (the screens' SelectionModel replaced them).
 */
open class EasyRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    fun interface OnItemClickListener {
        /** `true` if the click was consumed. */
        fun onItemClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean
    }

    fun interface OnItemLongClickListener {
        /** `true` if the long click was consumed. */
        fun onItemLongClick(parent: EasyRecyclerView, view: View, position: Int, id: Long): Boolean
    }

    private var easyAdapter: Adapter<*>? = null

    // Click and long click.
    private var prePressed = false
    private var hasPerformedLongPress = false
    private var motionView: View? = null
    private var motionPosition = 0
    private var pendingCheckForTap: CheckForTap? = null
    private var pendingCheckForLongPress: CheckForLongPress? = null
    private var unsetPressedState: UnsetPressedState? = null
    private var performClick: PerformClick? = null
    private val tmpPoint = FloatArray(2)
    private var touchSlop = -1
    private var startX = 0f
    private var startY = 0f
    private var onItemClickListener: OnItemClickListener? = null
    private var onItemLongClickListener: OnItemLongClickListener? = null

    /** Whether the selector is drawn on top of the rows or behind them. */
    private var drawSelectorOnTop = false

    /** The drawable drawn over the pressed row. */
    var selector: Drawable? = null
        set(sel) {
            field?.let {
                it.callback = null
                unscheduleDrawable(it)
            }
            field = sel
            val padding = Rect()
            sel?.getPadding(padding)
            selectionLeftPadding = padding.left
            selectionTopPadding = padding.top
            selectionRightPadding = padding.right
            selectionBottomPadding = padding.bottom
            sel?.callback = this
            updateSelectorState()
        }

    private var selectorPosition = INVALID_POSITION
    private val selectorRect = Rect()
    private var selectionLeftPadding = 0
    private var selectionTopPadding = 0
    private var selectionRightPadding = 0
    private var selectionBottomPadding = 0

    private var easyClipToPadding = false
    private var touchFromScrolling = false

    override fun setClipToPadding(clipToPadding: Boolean) {
        super.setClipToPadding(clipToPadding)
        easyClipToPadding = clipToPadding
    }

    override fun setAdapter(adapter: Adapter<*>?) {
        super.setAdapter(adapter)
        easyAdapter = adapter
    }

    private fun positionSelector(position: Int, sel: View) {
        val positionChanged = position != selectorPosition
        if (position != INVALID_POSITION) selectorPosition = position

        selectorRect.set(0, 0, sel.width, sel.height)
        // Adjust for the selection padding.
        selectorRect.left -= selectionLeftPadding
        selectorRect.top -= selectionTopPadding
        selectorRect.right += selectionRightPadding
        selectorRect.bottom += selectionBottomPadding

        val selector = selector ?: return
        if (positionChanged) {
            // Wipe out the current selector state, to start over in the new
            // position with a fresh state.
            selector.setVisible(false, false)
            selector.state = StateSet.NOTHING
        }
        selector.bounds = selectorRect
        if (positionChanged) {
            if (visibility == VISIBLE) selector.setVisible(true, false)
            updateSelectorState()
        }
    }

    private fun setSelectorHotspot(sel: View, x: Float, y: Float) {
        selector?.setHotspot(x - sel.left, y - sel.top)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val clipToPadding = easyClipToPadding
        var saveCount = 0
        if (clipToPadding) {
            saveCount = canvas.save()
            canvas.clipRect(
                scrollX + paddingLeft, scrollY + paddingTop,
                scrollX + right - left - paddingRight, scrollY + bottom - top - paddingBottom
            )
        }

        val view = motionView
        val itemCount = easyAdapter?.itemCount ?: 0
        val drawSelector = view != null && selectorPosition in 0 until itemCount
        if (drawSelector && !drawSelectorOnTop) drawSelector(canvas, view)

        super.dispatchDraw(canvas)

        if (drawSelector && drawSelectorOnTop) drawSelector(canvas, view)

        if (clipToPadding) canvas.restoreToCount(saveCount)
    }

    /** `true` while the selector should show: focused out of touch mode, or pressed. */
    private fun shouldShowSelector(): Boolean = (isFocused && !isInTouchMode) || isPressed

    private fun drawSelector(canvas: Canvas, view: View?) {
        val selector = selector
        if (view == null || selector == null || selectorRect.isEmpty) return
        val saveCount = canvas.save()
        canvas.translate(view.left.toFloat(), view.top.toFloat())
        selector.bounds = selectorRect
        selector.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    /** Draws the selector over the pressed row (`true`) or behind it (`false`, the default). */
    fun setDrawSelectorOnTop(onTop: Boolean) {
        drawSelectorOnTop = onTop
    }

    private fun updateSelectorState() {
        selector?.state = if (shouldShowSelector()) drawableState else StateSet.NOTHING
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        updateSelectorState()
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        // Not to the children: this view is pressed for the selector's
        // state, the rows are not.
    }

    override fun dispatchDrawableHotspotChanged(x: Float, y: Float) {
        // Not to the children: the touched row gets its hotspot directly.
    }

    override fun verifyDrawable(who: Drawable): Boolean = selector === who || super.verifyDrawable(who)

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        selector?.jumpToCurrentState()
    }

    /**
     * The checked look of each row, read from a selection the screen owns
     * (a SelectionModel). When set, rows are drawn from it as they attach
     * and on [refreshChecked].
     */
    var checkedProvider: ((position: Int) -> Boolean)? = null
        set(value) {
            field = value
            refreshChecked()
        }

    /** Redraws the checked look of the rows on screen from [checkedProvider]. */
    fun refreshChecked() {
        val provider = checkedProvider ?: return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val position = getChildAdapterPosition(child)
            if (position >= 0) setViewChecked(child, provider(position))
        }
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        val position = getChildAdapterPosition(child)
        if (position < 0) return
        val provider = checkedProvider ?: return
        setViewChecked(child, provider(position))
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) touchFromScrolling = scrollState == SCROLL_STATE_SETTLING
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) {
            // A disabled clickable view still consumes the touch, it just
            // does not respond to it.
            return isClickable || isLongClickable
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTouchDown(e)
            MotionEvent.ACTION_MOVE -> onTouchMove(e)
            MotionEvent.ACTION_UP -> onTouchUp(e)
            MotionEvent.ACTION_CANCEL -> onTouchCancel()
        }
        super.onTouchEvent(e)
        return true
    }

    private fun touchIgnored(): Boolean = isLayoutSuppressed || touchFromScrolling

    private fun onTouchDown(ev: MotionEvent) {
        if (touchIgnored()) return
        val x = ev.x
        val y = ev.y
        startX = x
        startY = y

        val view = findChildViewUnder(x, y)
        motionView = view
        motionPosition = if (view != null) getChildAdapterPosition(view) else NO_POSITION
        if (view != null && motionPosition >= 0 && view.isEnabled) {
            hasPerformedLongPress = false
            prePressed = true
            val tap = pendingCheckForTap ?: CheckForTap().also { pendingCheckForTap = it }
            tap.x = x
            tap.y = y
            tap.v = view
            tap.p = motionPosition
            postDelayed(tap, ViewConfiguration.getTapTimeout().toLong())
        } else {
            motionView = null
        }
    }

    private fun onTouchMove(ev: MotionEvent) {
        if (touchIgnored()) return
        if (touchSlop == -1) touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val view = motionView
        if (view != null && motionPosition >= 0 && hypot(ev.x - startX, ev.y - startY) >= touchSlop) {
            removeTapCallback()
            if (view.isPressed) {
                // Remove any future long press / tap checks.
                removeLongPressCallback()
                view.isPressed = false
                isPressed = false
            }
            updateSelectorState()
        }
    }

    private fun onTouchUp(ev: MotionEvent) {
        if (touchIgnored()) return
        val view = motionView ?: return
        val position = motionPosition
        if (position < 0) return
        val x = ev.x
        val y = ev.y
        if (view.isPressed || prePressed) {
            // Released before the row showed as pressed: show it now, before
            // the click is scheduled, so the user sees it.
            pressAt(view, x, y)
            positionSelector(position, view)
            selector?.let {
                (it.current as? TransitionDrawable)?.resetTransition()
                setSelectorHotspot(view, x, y)
            }
            if (!hasPerformedLongPress) {
                // A tap: drop the long-press check, and post the click so
                // the pressed look updates first.
                removeLongPressCallback()
                val click = performClick ?: PerformClick().also { performClick = it }
                click.v = view
                click.p = position
                click.rememberWindowAttachCount()
                if (!post(click)) click.run()
            }
            val unset = unsetPressedState ?: UnsetPressedState().also { unsetPressedState = it }
            unset.v = view
            if (prePressed) {
                postDelayed(unset, ViewConfiguration.getPressedStateDuration().toLong())
            } else if (!post(unset)) {
                // The post failed: unpress right now.
                unset.run()
            }
            removeTapCallback()
        }
        updateSelectorState()
    }

    private fun onTouchCancel() {
        if (touchIgnored()) return
        val view = motionView
        if (view != null && motionPosition >= 0) {
            view.isPressed = false
            isPressed = false
            removeTapCallback()
            removeLongPressCallback()
        }
    }

    /** Presses [view] (hotspot at the touch point) and this list. */
    private fun pressAt(view: View, x: Float, y: Float) {
        tmpPoint[0] = x + scrollX - view.left
        tmpPoint[1] = y + scrollY - view.top
        view.isPressed = true
        view.drawableHotspotChanged(tmpPoint[0], tmpPoint[1])
        isPressed = true
    }

    private fun removeTapCallback() {
        pendingCheckForTap?.let {
            prePressed = false
            removeCallbacks(it)
            it.v = null
        }
    }

    private fun removeLongPressCallback() {
        pendingCheckForLongPress?.let {
            removeCallbacks(it)
            it.v = null
        }
    }

    private fun performItemClick(view: View, position: Int, id: Long): Boolean {
        val listener = onItemClickListener ?: return false
        playSoundEffect(SoundEffectConstants.CLICK)
        listener.onItemClick(this, view, position, id)
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }

    private fun performItemLongPress(child: View, position: Int, id: Long): Boolean {
        val handled = onItemLongClickListener?.onItemLongClick(this, child, position, id) == true
        if (handled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return handled
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: OnItemLongClickListener?) {
        if (!isLongClickable) isLongClickable = true
        onItemLongClickListener = listener
    }

    private fun findViewForAdapterPosition(position: Int): View? = findViewHolderForAdapterPosition(position)?.itemView

    /** Shows the row pressed after the tap timeout and arms the long press. */
    private inner class CheckForTap : Runnable {
        var x = 0f
        var y = 0f
        var v: View? = null
        var p = 0

        override fun run() {
            val view = v
            val position = p
            v = null
            if (view == null || position < 0 || view !== findViewForAdapterPosition(position)) return
            prePressed = false
            pressAt(view, x, y)
            positionSelector(motionPosition, view)
            refreshDrawableState()

            val longPressTimeout = ViewConfiguration.getLongPressTimeout()
            val longClickable = isLongClickable
            selector?.let {
                (it.current as? TransitionDrawable)?.let { d ->
                    if (longClickable) d.startTransition(longPressTimeout) else d.resetTransition()
                }
                setSelectorHotspot(view, x, y)
            }
            if (longClickable) armLongPress(view, position, longPressTimeout)
        }
    }

    private fun armLongPress(view: View, position: Int, longPressTimeout: Int) {
        hasPerformedLongPress = false
        val longPress = pendingCheckForLongPress ?: CheckForLongPress().also { pendingCheckForLongPress = it }
        longPress.rememberWindowAttachCount()
        longPress.v = view
        longPress.p = position
        postDelayed(longPress, (longPressTimeout - ViewConfiguration.getTapTimeout()).toLong())
    }

    /** [view] still shows [position], attached and pressed. */
    private fun isStillPressed(view: View, position: Int): Boolean =
        view === findViewForAdapterPosition(position) && view.isPressed && view.parent != null

    /** Checks that the view is still in the window it was in when the runnable was armed. */
    private abstract inner class WindowRunnable : Runnable {
        private var originalAttachCount = 0

        fun rememberWindowAttachCount() {
            originalAttachCount = windowAttachCount
        }

        fun sameWindow(): Boolean = windowAttachCount == originalAttachCount
    }

    private inner class CheckForLongPress : WindowRunnable() {
        var v: View? = null
        var p = 0

        override fun run() {
            val view = v
            val position = p
            v = null
            val adapter = adapter ?: return
            if (view == null || position < 0 || !isStillPressed(view, position)) return
            val handled = sameWindow() && performItemLongPress(view, position, adapter.getItemId(position))
            if (handled) {
                hasPerformedLongPress = true
                view.isPressed = false
                isPressed = false
            }
        }
    }

    private inner class UnsetPressedState : Runnable {
        var v: View? = null

        override fun run() {
            v?.let {
                it.isPressed = false
                isPressed = false
                v = null
            }
        }
    }

    private inner class PerformClick : WindowRunnable() {
        var v: View? = null
        var p = 0

        override fun run() {
            val view = v
            val position = p
            v = null
            val adapter = easyAdapter ?: return
            if (view != null && position >= 0 && sameWindow()) performItemClick(view, position, adapter.getItemId(position))
        }
    }

    companion object {
        /** An invalid adapter position. */
        const val INVALID_POSITION = -1

        /** Shows [view] checked: Checkable views are checked, others activated. */
        private fun setViewChecked(view: View, checked: Boolean) {
            if (view is Checkable) view.isChecked = checked else view.isActivated = checked
        }
    }
}
