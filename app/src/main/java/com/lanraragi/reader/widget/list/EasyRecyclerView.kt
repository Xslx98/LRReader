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
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseBooleanArray
import android.util.StateSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.widget.Checkable
import androidx.core.os.ParcelCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * RecyclerView with item click / long-click listeners that draw a pressed
 * selector over the touched row (like AbsListView), and a custom
 * multiple-choice mode: [intoCustomChoiceMode], [setItemChecked],
 * [checkAll], [outOfCustomChoiceMode], reported to a
 * [CustomChoiceListener]; a checked row is shown checked (Checkable) or
 * activated.
 *
 * Ported to Kotlin from seven332's EasyRecyclerView library (0.1.1),
 * behaviour unchanged; only the custom choice mode the app uses is kept
 * (not the single / multiple / ActionMode-modal ones).
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

    interface CustomChoiceListener {
        fun onIntoCustomChoice(view: EasyRecyclerView)

        fun onOutOfCustomChoice(view: EasyRecyclerView)

        fun onItemCheckedStateChanged(view: EasyRecyclerView, position: Int, id: Long, checked: Boolean)
    }

    private var choiceMode = CHOICE_MODE_NONE
    private var customChoiceListener: CustomChoiceListener? = null

    /** `true` while in the custom choice mode. */
    var isInCustomChoice = false
        private set

    /** Guards against re-entering [outOfCustomChoiceMode] from its own unchecking. */
    private var outOfCustomChoiceModing = false
    private var tempCheckStates: SparseBooleanArray? = null

    /** Number of checked items. */
    var checkedItemCount = 0
        private set

    private var checkStates: SparseBooleanArray? = null

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
        checkStates?.clear()
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

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        val states = checkStates ?: return
        val position = getChildAdapterPosition(child)
        if (position >= 0) setViewChecked(child, states.get(position))
    }

    /** Whether [position] is checked (always `false` without a choice mode). */
    fun isItemChecked(position: Int): Boolean =
        choiceMode != CHOICE_MODE_NONE && checkStates?.get(position) == true

    /** The checked positions, or `null` without a choice mode. */
    val checkedItemPositions: SparseBooleanArray?
        get() = if (choiceMode != CHOICE_MODE_NONE) checkStates else null

    fun intoCustomChoiceMode() {
        if (choiceMode == CHOICE_MODE_MULTIPLE_CUSTOM && !isInCustomChoice) {
            isInCustomChoice = true
            customChoiceListener?.onIntoCustomChoice(this)
        }
    }

    fun outOfCustomChoiceMode() {
        if (choiceMode != CHOICE_MODE_MULTIPLE_CUSTOM || !isInCustomChoice || outOfCustomChoiceModing) return
        outOfCustomChoiceModing = true
        val states = checkStates ?: SparseBooleanArray()
        val temp = tempCheckStates ?: SparseBooleanArray().also { tempCheckStates = it }
        // Copy the check states, then uncheck what is still checked.
        temp.clear()
        for (i in 0 until states.size()) temp.put(states.keyAt(i), states.valueAt(i))
        for (i in 0 until temp.size()) {
            if (temp.valueAt(i)) setItemChecked(temp.keyAt(i), false)
        }
        isInCustomChoice = false
        customChoiceListener?.onOutOfCustomChoice(this)
        outOfCustomChoiceModing = false
    }

    private fun clearChoices() {
        checkStates?.clear()
        checkedItemCount = 0
        updateOnScreenCheckedViews()
    }

    /** Checks every item; needs [intoCustomChoiceMode] first. */
    fun checkAll() {
        if (choiceMode == CHOICE_MODE_NONE) return
        check(isInCustomChoice) { "Call intoCheckMode first" }
        val adapter = easyAdapter ?: return
        val states = checkStates ?: return
        for (i in 0 until adapter.itemCount) {
            val oldValue = states.get(i)
            states.put(i, true)
            if (!oldValue) checkedItemCount++
            customChoiceListener?.onItemCheckedStateChanged(this, i, adapter.getItemId(i), true)
        }
        updateOnScreenCheckedViews()
    }

    fun toggleItemChecked(position: Int) {
        val states = checkStates ?: return
        setItemChecked(position, !states.get(position))
    }

    /** Sets the checked state of [position]; needs [intoCustomChoiceMode] first. */
    fun setItemChecked(position: Int, value: Boolean) {
        if (choiceMode == CHOICE_MODE_NONE) return
        check(isInCustomChoice) { "Call intoCheckMode first" }
        val states = checkStates ?: return
        val oldValue = states.get(position)
        states.put(position, value)
        if (oldValue != value) {
            if (value) checkedItemCount++ else checkedItemCount--
        }
        val id = easyAdapter?.getItemId(position) ?: NO_ID
        customChoiceListener?.onItemCheckedStateChanged(this, position, id, value)
        updateOnScreenCheckedViews()
    }

    /**
     * [CHOICE_MODE_NONE] or [CHOICE_MODE_MULTIPLE_CUSTOM]; switching clears
     * the checked items.
     */
    fun setChoiceMode(choiceMode: Int) {
        this.choiceMode = choiceMode
        if (choiceMode != CHOICE_MODE_NONE) {
            if (checkStates == null) checkStates = SparseBooleanArray(0)
            if (choiceMode == CHOICE_MODE_MULTIPLE_CUSTOM) {
                if (tempCheckStates == null) tempCheckStates = SparseBooleanArray(0)
                clearChoices()
            }
        }
    }

    fun setCustomCheckedListener(listener: CustomChoiceListener?) {
        customChoiceListener = listener
    }

    /** Updates the checked look of every row on screen; only with a choice mode. */
    private fun updateOnScreenCheckedViews() {
        val states = checkStates ?: return
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            setViewChecked(child, states.get(getChildAdapterPosition(child)))
        }
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

    public override fun onSaveInstanceState(): Parcelable = SavedState(super.onSaveInstanceState()).also {
        it.choiceMode = choiceMode
        it.customChoice = isInCustomChoice
        it.checkedItemCount = checkedItemCount
        it.checkState = checkStates
    }

    public override fun onRestoreInstanceState(state: Parcelable?) {
        val ss = state as SavedState
        super.onRestoreInstanceState(ss.superState)
        setChoiceMode(ss.choiceMode)
        isInCustomChoice = ss.customChoice
        checkedItemCount = ss.checkedItemCount
        ss.checkState?.let { checkStates = it }
        updateOnScreenCheckedViews()
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

    /**
     * Not a View.BaseSavedState: AbsSavedState(Parcel) reads the super
     * state with a null class loader, and RecyclerView's needs its own
     * (BadParcelableException otherwise).
     */
    internal class SavedState : Parcelable {
        var choiceMode = 0
        var customChoice = false
        var checkedItemCount = 0
        var checkState: SparseBooleanArray? = null

        /** RecyclerView's own saved state. */
        val superState: Parcelable?

        constructor(superState: Parcelable?) {
            this.superState = superState
        }

        private constructor(parcel: Parcel) {
            superState = ParcelCompat.readParcelable(parcel, RecyclerView::class.java.classLoader, Parcelable::class.java)
            choiceMode = parcel.readInt()
            customChoice = parcel.readInt() != 0
            checkedItemCount = parcel.readInt()
            checkState = parcel.readSparseBooleanArray()
        }

        override fun describeContents(): Int = 0

        override fun writeToParcel(out: Parcel, flags: Int) {
            out.writeParcelable(superState, flags)
            out.writeInt(choiceMode)
            out.writeInt(if (customChoice) 1 else 0)
            out.writeInt(checkedItemCount)
            out.writeSparseBooleanArray(checkState)
        }

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(parcel: Parcel): SavedState = SavedState(parcel)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    companion object {
        /** An invalid adapter position. */
        const val INVALID_POSITION = -1

        /** No choices. */
        const val CHOICE_MODE_NONE = 0

        /** Multiple choices in a custom selection mode. */
        const val CHOICE_MODE_MULTIPLE_CUSTOM = 4

        /** Shows [view] checked: Checkable views are checked, others activated. */
        private fun setViewChecked(view: View, checked: Boolean) {
            if (view is Checkable) view.isChecked = checked else view.isActivated = checked
        }
    }
}
