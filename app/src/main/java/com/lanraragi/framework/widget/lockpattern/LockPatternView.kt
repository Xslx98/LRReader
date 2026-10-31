/*
 * Copyright (C) 2007 The Android Open Source Project
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

@file:Suppress(
    "LargeClass",
    "LongMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "UnusedParameter",
    "UseCheckOrError",
    "UseRequire",
) // TODO(2.4-idiom)

package com.lanraragi.framework.widget.lockpattern

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.Interpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import com.lanraragi.reader.R
import com.lanraragi.reader.util.isLightTheme

/**
 * Displays and detects the user's unlock attempt, which is a drag of a finger
 * across 9 regions of the screen.
 *
 * Is also capable of displaying a static pattern in "in progress", "wrong" or
 * "correct" states.
 */
class LockPatternView : View {

    private val mCellStates: Array<Array<CellState>>

    private val mDotSize: Int
    private val mDotSizeActivated: Int
    private val mPathWidth: Int

    private val mPaint = Paint()
    private val mPathPaint = Paint()

    private var mOnPatternListener: OnPatternListener? = null
    private val mPattern = ArrayList<Cell>(9)

    /**
     * Lookup table for the circles of the pattern we are currently drawing.
     * This will be the cells of the complete pattern unless we are animating,
     * in which case we use this to hold the cells we are drawing for the in
     * progress animation.
     */
    private val mPatternDrawLookup = Array(3) { BooleanArray(3) }

    /**
     * the in progress point:
     * - during interaction: where the user's finger is
     * - during animation: the current tip of the animating line
     */
    private var mInProgressX = -1f
    private var mInProgressY = -1f

    private var mAnimatingPeriodStart: Long = 0

    private var mPatternDisplayMode = DisplayMode.Correct
    private var mInputEnabled = true
    private var mInStealthMode = false
    private var mEnableHapticFeedback = true
    private var mPatternInProgress = false

    private val mHitFactor = 0.6f

    private var mSquareWidth = 0f
    private var mSquareHeight = 0f

    private val mCurrentPath = Path()
    private val mInvalidate = Rect()
    private val mTmpInvalidateRect = Rect()

    private val mRegularColor: Int
    private val mErrorColor: Int
    private val mSuccessColor: Int

    private val mFastOutSlowInInterpolator: Interpolator
    private val mLinearOutSlowInInterpolator: Interpolator

    /**
     * Represents a cell in the 3 X 3 matrix of the unlock pattern view.
     */
    class Cell private constructor(
        /** The row of the cell. */
        val row: Int,
        /** The column of the cell. */
        val column: Int,
    ) {
        init {
            checkRange(row, column)
        }

        override fun toString(): String = "(row=$row,clmn=$column)"

        companion object {
            // keep # objects limited to 9
            private val sCells = Array(3) { i -> Array(3) { j -> Cell(i, j) } }

            /**
             * @param row The row of the cell.
             * @param column The column of the cell.
             */
            @Synchronized
            fun of(row: Int, column: Int): Cell {
                checkRange(row, column)
                return sCells[row][column]
            }

            private fun checkRange(row: Int, column: Int) {
                if (row < 0 || row > 2) {
                    throw IllegalArgumentException("row must be in range 0-2")
                }
                if (column < 0 || column > 2) {
                    throw IllegalArgumentException("column must be in range 0-2")
                }
            }
        }
    }

    class CellState {
        var scale = 1.0f
        var translateY = 0.0f
        var alpha = 1.0f
        var size = 0f
        var lineEndX = Float.MIN_VALUE
        var lineEndY = Float.MIN_VALUE
        var lineAnimator: ValueAnimator? = null
    }

    /**
     * How to display the current pattern.
     */
    enum class DisplayMode {

        /**
         * The pattern drawn is correct (i.e draw it in a friendly color)
         */
        Correct,

        /**
         * Animate the pattern (for demo, and help).
         */
        Animate,

        /**
         * The pattern is wrong (i.e draw a foreboding color)
         */
        Wrong,
    }

    /**
     * The call back interface for detecting patterns entered by the user.
     */
    interface OnPatternListener {

        /**
         * A new pattern has begun.
         */
        fun onPatternStart()

        /**
         * The pattern was cleared.
         */
        fun onPatternCleared()

        /**
         * The user extended the pattern currently being drawn by one cell.
         * @param pattern The pattern with newly added cell.
         */
        fun onPatternCellAdded(pattern: List<Cell>)

        /**
         * A pattern was detected from the user.
         * @param pattern The pattern.
         */
        fun onPatternDetected(pattern: List<Cell>)
    }

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    init {
        isClickable = true

        mPathPaint.isAntiAlias = true
        mPathPaint.isDither = true

        // getColor(id, null) is what the deprecated getColor(id) delegates to.
        mRegularColor = resources.getColor(
            if (context.isLightTheme()) {
                R.color.lock_pattern_view_regular_color_light
            } else {
                R.color.lock_pattern_view_regular_color_dark
            },
            null,
        )
        mErrorColor = resources.getColor(R.color.lock_pattern_view_error_color, null)
        mSuccessColor = resources.getColor(R.color.lock_pattern_view_success_color, null)

        val pathColor = mRegularColor
        mPathPaint.color = pathColor

        mPathPaint.style = Paint.Style.STROKE
        mPathPaint.strokeJoin = Paint.Join.ROUND
        mPathPaint.strokeCap = Paint.Cap.ROUND

        mPathWidth = resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_line_width)
        mPathPaint.strokeWidth = mPathWidth.toFloat()

        mDotSize = resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_size)
        mDotSizeActivated = resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_size_activated)

        mPaint.isAntiAlias = true
        mPaint.isDither = true

        mCellStates = Array(3) {
            Array(3) {
                CellState().also { it.size = mDotSize.toFloat() }
            }
        }

        mFastOutSlowInInterpolator = FastOutSlowInInterpolator()
        mLinearOutSlowInInterpolator = LinearOutSlowInInterpolator()
    }

    fun getCellStates(): Array<Array<CellState>> = mCellStates

    val cellSize: Int
        get() = mPattern.size

    val patternString: String
        get() = LockPatternUtils.patternToString(mPattern)

    /**
     * Whether the view is in stealth mode.  If true, there will be no
     * visible feedback as the user enters the pattern.
     */
    var isInStealthMode: Boolean
        get() = mInStealthMode
        set(inStealthMode) {
            mInStealthMode = inStealthMode
        }

    /**
     * Whether the view will use tactile feedback.  If true, there will be
     * tactile feedback as the user enters the pattern.
     */
    var isTactileFeedbackEnabled: Boolean
        get() = mEnableHapticFeedback
        set(tactileFeedbackEnabled) {
            mEnableHapticFeedback = tactileFeedbackEnabled
        }

    /**
     * Set the call back for pattern detection.
     * @param onPatternListener The call back.
     */
    fun setOnPatternListener(onPatternListener: OnPatternListener?) {
        mOnPatternListener = onPatternListener
    }

    /**
     * Set the pattern explicitely (rather than waiting for the user to input
     * a pattern).
     * @param displayMode How to display the pattern.
     * @param pattern The pattern.
     */
    fun setPattern(displayMode: DisplayMode, pattern: List<Cell>) {
        mPattern.clear()
        mPattern.addAll(pattern)
        clearPatternDrawLookup()
        for (cell in pattern) {
            mPatternDrawLookup[cell.row][cell.column] = true
        }

        setDisplayMode(displayMode)
    }

    /**
     * Set the display mode of the current pattern.  This can be useful, for
     * instance, after detecting a pattern to tell this view whether change the
     * in progress result to correct or wrong.
     * @param displayMode The display mode.
     */
    fun setDisplayMode(displayMode: DisplayMode) {
        mPatternDisplayMode = displayMode
        if (displayMode == DisplayMode.Animate) {
            if (mPattern.size == 0) {
                throw IllegalStateException(
                    "you must have a pattern to " +
                        "animate if you want to set the display mode to animate",
                )
            }
            mAnimatingPeriodStart = SystemClock.elapsedRealtime()
            val first = mPattern[0]
            mInProgressX = getCenterXForColumn(first.column)
            mInProgressY = getCenterYForRow(first.row)
            clearPatternDrawLookup()
        }
        invalidate()
    }

    /**
     * Get the display mode of the current pattern.
     * @return the display mode of the current pattern
     */
    fun getDisplayMode(): DisplayMode = mPatternDisplayMode

    private fun notifyCellAdded() {
        mOnPatternListener?.onPatternCellAdded(mPattern)
    }

    private fun notifyPatternStarted() {
        mOnPatternListener?.onPatternStart()
    }

    private fun notifyPatternDetected() {
        mOnPatternListener?.onPatternDetected(mPattern)
    }

    private fun notifyPatternCleared() {
        mOnPatternListener?.onPatternCleared()
    }

    /**
     * Clear the pattern.
     */
    fun clearPattern() {
        resetPattern()
    }

    /**
     * Reset all pattern state.
     */
    private fun resetPattern() {
        mPattern.clear()
        clearPatternDrawLookup()
        mPatternDisplayMode = DisplayMode.Correct
        invalidate()
    }

    /**
     * Clear the pattern lookup table.
     */
    private fun clearPatternDrawLookup() {
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                mPatternDrawLookup[i][j] = false
            }
        }
    }

    /**
     * Disable input (for instance when displaying a message that will
     * timeout so user doesn't get view into messy state).
     */
    fun disableInput() {
        mInputEnabled = false
    }

    /**
     * Enable input.
     */
    fun enableInput() {
        mInputEnabled = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = w - paddingLeft - paddingRight
        mSquareWidth = width / 3.0f

        val height = h - paddingTop - paddingBottom
        mSquareHeight = height / 3.0f
    }

    private fun resolveMeasured(measureSpec: Int, desired: Int): Int {
        val specSize = MeasureSpec.getSize(measureSpec)
        return when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.UNSPECIFIED -> desired
            MeasureSpec.AT_MOST -> Math.max(specSize, desired)
            else -> specSize // MeasureSpec.EXACTLY
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minimumWidth = suggestedMinimumWidth
        val minimumHeight = suggestedMinimumHeight
        val viewWidth = resolveMeasured(widthMeasureSpec, minimumWidth)
        val viewHeight = resolveMeasured(heightMeasureSpec, minimumHeight)

        // Always square: the minimum of width and height.
        val side = Math.min(viewWidth, viewHeight)
        setMeasuredDimension(side, side)
    }

    /**
     * Determines whether the point x, y will add a new point to the current
     * pattern (in addition to finding the cell, also makes heuristic choices
     * such as filling in gaps based on current pattern).
     * @param x The x coordinate.
     * @param y The y coordinate.
     */
    private fun detectAndAddHit(x: Float, y: Float): Cell? {
        val cell = checkForNewHit(x, y)
        if (cell != null) {
            // check for gaps in existing pattern
            var fillInGapCell: Cell? = null
            val pattern = mPattern
            if (!pattern.isEmpty()) {
                val lastCell = pattern[pattern.size - 1]
                val dRow = cell.row - lastCell.row
                val dColumn = cell.column - lastCell.column

                var fillInRow = lastCell.row
                var fillInColumn = lastCell.column

                if (Math.abs(dRow) == 2 && Math.abs(dColumn) != 1) {
                    fillInRow = lastCell.row + if (dRow > 0) 1 else -1
                }

                if (Math.abs(dColumn) == 2 && Math.abs(dRow) != 1) {
                    fillInColumn = lastCell.column + if (dColumn > 0) 1 else -1
                }

                fillInGapCell = Cell.of(fillInRow, fillInColumn)
            }

            if (fillInGapCell != null &&
                !mPatternDrawLookup[fillInGapCell.row][fillInGapCell.column]
            ) {
                addCellToPattern(fillInGapCell)
            }
            addCellToPattern(cell)
            if (mEnableHapticFeedback) {
                // Honours the system touch-feedback switch (no FLAG_IGNORE_GLOBAL_SETTING).
                performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
                )
            }
            return cell
        }
        return null
    }

    private fun addCellToPattern(newCell: Cell) {
        mPatternDrawLookup[newCell.row][newCell.column] = true
        mPattern.add(newCell)
        if (!mInStealthMode) {
            startCellActivatedAnimation(newCell)
        }
        notifyCellAdded()
    }

    private fun startCellActivatedAnimation(cell: Cell) {
        val cellState = mCellStates[cell.row][cell.column]
        startSizeAnimation(
            mDotSize.toFloat(), mDotSizeActivated.toFloat(), 96L, mLinearOutSlowInInterpolator,
            cellState,
        ) {
            startSizeAnimation(
                mDotSizeActivated.toFloat(), mDotSize.toFloat(), 192L, mFastOutSlowInInterpolator,
                cellState, null,
            )
        }
        startLineEndAnimation(
            cellState, mInProgressX, mInProgressY,
            getCenterXForColumn(cell.column), getCenterYForRow(cell.row),
        )
    }

    private fun startLineEndAnimation(
        state: CellState,
        startX: Float,
        startY: Float,
        targetX: Float,
        targetY: Float,
    ) {
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f)
        valueAnimator.addUpdateListener { animation ->
            val t = animation.animatedValue as Float
            state.lineEndX = (1 - t) * startX + t * targetX
            state.lineEndY = (1 - t) * startY + t * targetY
            invalidate()
        }
        valueAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                state.lineAnimator = null
            }
        })
        valueAnimator.interpolator = mFastOutSlowInInterpolator
        valueAnimator.duration = 100L
        valueAnimator.start()
        state.lineAnimator = valueAnimator
    }

    private fun startSizeAnimation(
        start: Float,
        end: Float,
        duration: Long,
        interpolator: Interpolator,
        state: CellState,
        endRunnable: Runnable?,
    ) {
        val valueAnimator = ValueAnimator.ofFloat(start, end)
        valueAnimator.addUpdateListener { animation ->
            state.size = animation.animatedValue as Float
            invalidate()
        }
        if (endRunnable != null) {
            valueAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    endRunnable.run()
                }
            })
        }
        valueAnimator.interpolator = interpolator
        valueAnimator.duration = duration
        valueAnimator.start()
    }

    // helper method to find which cell a point maps to
    private fun checkForNewHit(x: Float, y: Float): Cell? {
        val rowHit = getRowHit(y)
        if (rowHit < 0) {
            return null
        }
        val columnHit = getColumnHit(x)
        if (columnHit < 0) {
            return null
        }

        if (mPatternDrawLookup[rowHit][columnHit]) {
            return null
        }
        return Cell.of(rowHit, columnHit)
    }

    /**
     * Helper method to find the row that y falls into.
     * @param y The y coordinate
     * @return The row that y falls in, or -1 if it falls in no row.
     */
    private fun getRowHit(y: Float): Int {
        val squareHeight = mSquareHeight
        val hitSize = squareHeight * mHitFactor

        val offset = paddingTop + (squareHeight - hitSize) / 2f
        for (i in 0 until 3) {
            val hitTop = offset + squareHeight * i
            if (y >= hitTop && y <= hitTop + hitSize) {
                return i
            }
        }
        return -1
    }

    /**
     * Helper method to find the column x fallis into.
     * @param x The x coordinate.
     * @return The column that x falls in, or -1 if it falls in no column.
     */
    private fun getColumnHit(x: Float): Int {
        val squareWidth = mSquareWidth
        val hitSize = squareWidth * mHitFactor

        val offset = paddingLeft + (squareWidth - hitSize) / 2f
        for (i in 0 until 3) {
            val hitLeft = offset + squareWidth * i
            if (x >= hitLeft && x <= hitLeft + hitSize) {
                return i
            }
        }
        return -1
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (accessibilityManager.isTouchExplorationEnabled) {
            val action = event.action
            when (action) {
                MotionEvent.ACTION_HOVER_ENTER -> event.action = MotionEvent.ACTION_DOWN
                MotionEvent.ACTION_HOVER_MOVE -> event.action = MotionEvent.ACTION_MOVE
                MotionEvent.ACTION_HOVER_EXIT -> event.action = MotionEvent.ACTION_UP
            }
            onTouchEvent(event)
            event.action = action
        }
        return super.onHoverEvent(event)
    }

    override fun performClick(): Boolean {
        // A finished stroke counts as a click for accessibility services.
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!mInputEnabled || !isEnabled) {
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handleActionDown(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handleActionUp(event)
                performClick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleActionMove(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mPatternInProgress) {
                    mPatternInProgress = false
                    resetPattern()
                    notifyPatternCleared()
                }
                return true
            }
        }
        return false
    }

    private fun handleActionMove(event: MotionEvent) {
        // Handle all recent motion events so we don't skip any cells even when the device
        // is busy...
        val radius = mPathWidth.toFloat()
        val historySize = event.historySize
        mTmpInvalidateRect.setEmpty()
        var invalidateNow = false
        for (i in 0 until historySize + 1) {
            val x = if (i < historySize) event.getHistoricalX(i) else event.x
            val y = if (i < historySize) event.getHistoricalY(i) else event.y
            val hitCell = detectAndAddHit(x, y)
            val patternSize = mPattern.size
            if (hitCell != null && patternSize == 1) {
                mPatternInProgress = true
                notifyPatternStarted()
            }
            // note current x and y for rubber banding of in progress patterns
            val dx = Math.abs(x - mInProgressX)
            val dy = Math.abs(y - mInProgressY)
            if (dx > DRAG_THRESHHOLD || dy > DRAG_THRESHHOLD) {
                invalidateNow = true
            }

            if (mPatternInProgress && patternSize > 0) {
                val pattern = mPattern
                val lastCell = pattern[patternSize - 1]
                val lastCellCenterX = getCenterXForColumn(lastCell.column)
                val lastCellCenterY = getCenterYForRow(lastCell.row)

                // Adjust for drawn segment from last cell to (x,y). Radius accounts for line width.
                var left = Math.min(lastCellCenterX, x) - radius
                var right = Math.max(lastCellCenterX, x) + radius
                var top = Math.min(lastCellCenterY, y) - radius
                var bottom = Math.max(lastCellCenterY, y) + radius

                // Invalidate between the pattern's new cell and the pattern's previous cell
                if (hitCell != null) {
                    val width = mSquareWidth * 0.5f
                    val height = mSquareHeight * 0.5f
                    val hitCellCenterX = getCenterXForColumn(hitCell.column)
                    val hitCellCenterY = getCenterYForRow(hitCell.row)

                    left = Math.min(hitCellCenterX - width, left)
                    right = Math.max(hitCellCenterX + width, right)
                    top = Math.min(hitCellCenterY - height, top)
                    bottom = Math.max(hitCellCenterY + height, bottom)
                }

                // Invalidate between the pattern's last cell and the previous location
                mTmpInvalidateRect.union(
                    Math.round(left), Math.round(top),
                    Math.round(right), Math.round(bottom),
                )
            }
        }
        mInProgressX = event.x
        mInProgressY = event.y

        // To save updates, we only invalidate if the user moved beyond a certain amount.
        if (invalidateNow) {
            mInvalidate.union(mTmpInvalidateRect)
            @Suppress("DEPRECATION")
            invalidate(mInvalidate)
            mInvalidate.set(mTmpInvalidateRect)
        }
    }

    private fun handleActionUp(event: MotionEvent) {
        // report pattern detected
        if (!mPattern.isEmpty()) {
            mPatternInProgress = false
            cancelLineAnimations()
            notifyPatternDetected()
            invalidate()
        }
    }

    private fun cancelLineAnimations() {
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                val state = mCellStates[i][j]
                val lineAnimator = state.lineAnimator
                if (lineAnimator != null) {
                    lineAnimator.cancel()
                    state.lineEndX = Float.MIN_VALUE
                    state.lineEndY = Float.MIN_VALUE
                }
            }
        }
    }

    private fun handleActionDown(event: MotionEvent) {
        resetPattern()
        val x = event.x
        val y = event.y
        val hitCell = detectAndAddHit(x, y)
        if (hitCell != null) {
            mPatternInProgress = true
            mPatternDisplayMode = DisplayMode.Correct
            notifyPatternStarted()
        } else if (mPatternInProgress) {
            mPatternInProgress = false
            notifyPatternCleared()
        }
        if (hitCell != null) {
            val startX = getCenterXForColumn(hitCell.column)
            val startY = getCenterYForRow(hitCell.row)

            val widthOffset = mSquareWidth / 2f
            val heightOffset = mSquareHeight / 2f

            @Suppress("DEPRECATION")
            invalidate(
                (startX - widthOffset).toInt(), (startY - heightOffset).toInt(),
                (startX + widthOffset).toInt(), (startY + heightOffset).toInt(),
            )
        }
        mInProgressX = x
        mInProgressY = y
    }

    private fun getCenterXForColumn(column: Int): Float {
        return paddingLeft + column * mSquareWidth + mSquareWidth / 2f
    }

    private fun getCenterYForRow(row: Int): Float {
        return paddingTop + row * mSquareHeight + mSquareHeight / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val pattern = mPattern
        val count = pattern.size
        val drawLookup = mPatternDrawLookup

        if (mPatternDisplayMode == DisplayMode.Animate) {
            // figure out which circles to draw

            // + 1 so we pause on complete pattern
            val oneCycle = (count + 1) * MILLIS_PER_CIRCLE_ANIMATING
            val spotInCycle = (SystemClock.elapsedRealtime() - mAnimatingPeriodStart).toInt() % oneCycle
            val numCircles = spotInCycle / MILLIS_PER_CIRCLE_ANIMATING

            clearPatternDrawLookup()
            for (i in 0 until numCircles) {
                val cell = pattern[i]
                drawLookup[cell.row][cell.column] = true
            }

            // figure out in progress portion of ghosting line

            val needToUpdateInProgressPoint = numCircles > 0 && numCircles < count

            if (needToUpdateInProgressPoint) {
                val percentageOfNextCircle =
                    (spotInCycle % MILLIS_PER_CIRCLE_ANIMATING).toFloat() / MILLIS_PER_CIRCLE_ANIMATING

                val currentCell = pattern[numCircles - 1]
                val centerX = getCenterXForColumn(currentCell.column)
                val centerY = getCenterYForRow(currentCell.row)

                val nextCell = pattern[numCircles]
                val dx = percentageOfNextCircle * (getCenterXForColumn(nextCell.column) - centerX)
                val dy = percentageOfNextCircle * (getCenterYForRow(nextCell.row) - centerY)
                mInProgressX = centerX + dx
                mInProgressY = centerY + dy
            }
            // UPSTREAM-LEGACY (AOSP): potential infinite loop in pattern detection
            invalidate()
        }

        val currentPath = mCurrentPath
        currentPath.rewind()

        // draw the circles
        for (i in 0 until 3) {
            val centerY = getCenterYForRow(i)
            for (j in 0 until 3) {
                val cellState = mCellStates[i][j]
                val centerX = getCenterXForColumn(j)
                val size = cellState.size * cellState.scale
                val translationY = cellState.translateY
                drawCircle(
                    canvas, centerX.toInt().toFloat(), centerY.toInt() + translationY,
                    size, drawLookup[i][j], cellState.alpha,
                )
            }
        }

        // UPSTREAM-LEGACY (AOSP): path should be cached per cell hit-detection
        // only the last segment of the path should be computed here
        // draw the path of the pattern (unless we are in stealth mode)
        val drawPath = !mInStealthMode

        if (drawPath) {
            mPathPaint.color = getCurrentColor(true /* partOfPattern */)

            var anyCircles = false
            var lastX = 0f
            var lastY = 0f
            for (i in 0 until count) {
                val cell = pattern[i]

                // only draw the part of the pattern stored in
                // the lookup table (this is only different in the case
                // of animation).
                if (!drawLookup[cell.row][cell.column]) {
                    break
                }
                anyCircles = true

                val centerX = getCenterXForColumn(cell.column)
                val centerY = getCenterYForRow(cell.row)
                if (i != 0) {
                    val state = mCellStates[cell.row][cell.column]
                    currentPath.rewind()
                    currentPath.moveTo(lastX, lastY)
                    if (state.lineEndX != Float.MIN_VALUE && state.lineEndY != Float.MIN_VALUE) {
                        currentPath.lineTo(state.lineEndX, state.lineEndY)
                    } else {
                        currentPath.lineTo(centerX, centerY)
                    }
                    canvas.drawPath(currentPath, mPathPaint)
                }
                lastX = centerX
                lastY = centerY
            }

            // draw last in progress section
            if ((mPatternInProgress || mPatternDisplayMode == DisplayMode.Animate) && anyCircles) {
                currentPath.rewind()
                currentPath.moveTo(lastX, lastY)
                currentPath.lineTo(mInProgressX, mInProgressY)

                mPathPaint.alpha = (calculateLastSegmentAlpha(mInProgressX, mInProgressY, lastX, lastY) * 255f).toInt()
                canvas.drawPath(currentPath, mPathPaint)
            }
        }
    }

    private fun calculateLastSegmentAlpha(x: Float, y: Float, lastX: Float, lastY: Float): Float {
        val diffX = x - lastX
        val diffY = y - lastY
        val dist = Math.sqrt((diffX * diffX + diffY * diffY).toDouble()).toFloat()
        val frac = dist / mSquareWidth
        return Math.min(1f, Math.max(0f, (frac - 0.3f) * 4f))
    }

    private fun getCurrentColor(partOfPattern: Boolean): Int {
        return if (!partOfPattern || mInStealthMode || mPatternInProgress) {
            // unselected circle
            mRegularColor
        } else if (mPatternDisplayMode == DisplayMode.Wrong) {
            // the pattern is wrong
            mErrorColor
        } else if (mPatternDisplayMode == DisplayMode.Correct ||
            mPatternDisplayMode == DisplayMode.Animate
        ) {
            mSuccessColor
        } else {
            throw IllegalStateException("unknown display mode $mPatternDisplayMode")
        }
    }

    /**
     * @param partOfPattern Whether this circle is part of the pattern.
     */
    private fun drawCircle(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        partOfPattern: Boolean,
        alpha: Float,
    ) {
        mPaint.color = getCurrentColor(partOfPattern)
        mPaint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(centerX, centerY, size / 2, mPaint)
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        return SavedState(
            superState,
            LockPatternUtils.patternToString(mPattern),
            mPatternDisplayMode.ordinal,
            mInputEnabled, mInStealthMode, mEnableHapticFeedback,
        )
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val ss = state as SavedState
        super.onRestoreInstanceState(ss.superState)
        setPattern(
            DisplayMode.Correct,
            LockPatternUtils.stringToPattern(ss.serializedPattern),
        )
        mPatternDisplayMode = DisplayMode.entries[ss.displayMode]
        mInputEnabled = ss.isInputEnabled
        mInStealthMode = ss.isInStealthMode
        mEnableHapticFeedback = ss.isTactileFeedbackEnabled
    }

    /**
     * The parecelable for saving and restoring a lock pattern view.
     */
    private class SavedState : BaseSavedState {

        val serializedPattern: String
        val displayMode: Int
        val isInputEnabled: Boolean
        val isInStealthMode: Boolean
        val isTactileFeedbackEnabled: Boolean

        /**
         * Constructor called from [LockPatternView.onSaveInstanceState]
         */
        constructor(
            superState: Parcelable?,
            serializedPattern: String,
            displayMode: Int,
            inputEnabled: Boolean,
            inStealthMode: Boolean,
            tactileFeedbackEnabled: Boolean,
        ) : super(superState) {
            this.serializedPattern = serializedPattern
            this.displayMode = displayMode
            isInputEnabled = inputEnabled
            isInStealthMode = inStealthMode
            isTactileFeedbackEnabled = tactileFeedbackEnabled
        }

        /**
         * Constructor called from [CREATOR]
         */
        @SuppressLint("ParcelClassLoader")
        constructor(source: Parcel) : super(source) {
            // writeToParcel always writes a non-null string.
            serializedPattern = checkNotNull(source.readString())
            displayMode = source.readInt()
            isInputEnabled = source.readValue(null) as Boolean
            isInStealthMode = source.readValue(null) as Boolean
            isTactileFeedbackEnabled = source.readValue(null) as Boolean
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeString(serializedPattern)
            dest.writeInt(displayMode)
            dest.writeValue(isInputEnabled)
            dest.writeValue(isInStealthMode)
            dest.writeValue(isTactileFeedbackEnabled)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)

                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    companion object {
        /**
         * How many milliseconds we spend animating each circle of a lock pattern
         * if the animating mode is set.  The entire animation should take this
         * constant * the length of the pattern to complete.
         */
        private const val MILLIS_PER_CIRCLE_ANIMATING = 700

        /**
         * This can be used to avoid updating the display for very small motions or noisy panels.
         * It didn't seem to have much impact on the devices tested, so currently set to 0.
         */
        private const val DRAG_THRESHHOLD = 0.0f
    }
}
