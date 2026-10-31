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

    val cellStates: Array<Array<CellState>>

    private val dotSize: Int
    private val dotSizeActivated: Int
    private val pathWidth: Int

    private val paint = Paint()
    private val pathPaint = Paint()

    /** The call back for pattern detection. */
    var onPatternListener: OnPatternListener? = null
    private val pattern = ArrayList<Cell>(9)

    /**
     * Lookup table for the circles of the pattern we are currently drawing.
     * This will be the cells of the complete pattern unless we are animating,
     * in which case we use this to hold the cells we are drawing for the in
     * progress animation.
     */
    private val patternDrawLookup = Array(3) { BooleanArray(3) }

    /**
     * the in progress point:
     * - during interaction: where the user's finger is
     * - during animation: the current tip of the animating line
     */
    private var inProgressX = -1f
    private var inProgressY = -1f

    private var animatingPeriodStart: Long = 0

    private var patternDisplayMode = DisplayMode.Correct
    private var inputEnabled = true

    /**
     * Whether the view is in stealth mode.  If true, there will be no
     * visible feedback as the user enters the pattern.
     */
    var isInStealthMode = false

    /**
     * Whether the view will use tactile feedback.  If true, there will be
     * tactile feedback as the user enters the pattern.
     */
    var isTactileFeedbackEnabled = true

    private var patternInProgress = false

    private var squareWidth = 0f
    private var squareHeight = 0f

    private val currentPath = Path()
    private val invalidateRect = Rect()
    private val tmpInvalidateRect = Rect()

    private val regularColor: Int
    private val errorColor: Int
    private val successColor: Int

    private val fastOutSlowInInterpolator: Interpolator
    private val linearOutSlowInInterpolator: Interpolator

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
            private val cells = Array(3) { i -> Array(3) { j -> Cell(i, j) } }

            /**
             * @param row The row of the cell.
             * @param column The column of the cell.
             */
            @Synchronized
            fun of(row: Int, column: Int): Cell {
                checkRange(row, column)
                return cells[row][column]
            }

            private fun checkRange(row: Int, column: Int) {
                require(row in 0..2) { "row must be in range 0-2" }
                require(column in 0..2) { "column must be in range 0-2" }
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

        pathPaint.isAntiAlias = true
        pathPaint.isDither = true

        // getColor(id, null) is what the deprecated getColor(id) delegates to.
        regularColor = resources.getColor(
            if (context.isLightTheme()) {
                R.color.lock_pattern_view_regular_color_light
            } else {
                R.color.lock_pattern_view_regular_color_dark
            },
            null,
        )
        errorColor = resources.getColor(R.color.lock_pattern_view_error_color, null)
        successColor = resources.getColor(R.color.lock_pattern_view_success_color, null)

        val pathColor = regularColor
        pathPaint.color = pathColor

        pathPaint.style = Paint.Style.STROKE
        pathPaint.strokeJoin = Paint.Join.ROUND
        pathPaint.strokeCap = Paint.Cap.ROUND

        pathWidth = resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_line_width)
        pathPaint.strokeWidth = pathWidth.toFloat()

        dotSize = resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_size)
        dotSizeActivated = resources.getDimensionPixelSize(R.dimen.lock_pattern_dot_size_activated)

        paint.isAntiAlias = true
        paint.isDither = true

        cellStates = Array(3) {
            Array(3) {
                CellState().also { it.size = dotSize.toFloat() }
            }
        }

        fastOutSlowInInterpolator = FastOutSlowInInterpolator()
        linearOutSlowInInterpolator = LinearOutSlowInInterpolator()
    }

    val cellSize: Int
        get() = pattern.size

    val patternString: String
        get() = LockPatternUtils.patternToString(pattern)

    /**
     * Set the pattern explicitely (rather than waiting for the user to input
     * a pattern).
     * @param displayMode How to display the pattern.
     * @param pattern The pattern.
     */
    fun setPattern(displayMode: DisplayMode, pattern: List<Cell>) {
        this.pattern.clear()
        this.pattern.addAll(pattern)
        clearPatternDrawLookup()
        for (cell in pattern) {
            patternDrawLookup[cell.row][cell.column] = true
        }

        applyDisplayMode(displayMode)
    }

    /**
     * The display mode of the current pattern.  Setting it can be useful, for
     * instance, after detecting a pattern to tell this view whether change the
     * in progress result to correct or wrong.
     */
    var displayMode: DisplayMode
        get() = patternDisplayMode
        set(displayMode) {
            applyDisplayMode(displayMode)
        }

    private fun applyDisplayMode(displayMode: DisplayMode) {
        patternDisplayMode = displayMode
        if (displayMode == DisplayMode.Animate) {
            check(pattern.isNotEmpty()) {
                "you must have a pattern to animate if you want to set the display mode to animate"
            }
            animatingPeriodStart = SystemClock.elapsedRealtime()
            val first = pattern[0]
            inProgressX = getCenterXForColumn(first.column)
            inProgressY = getCenterYForRow(first.row)
            clearPatternDrawLookup()
        }
        invalidate()
    }

    private fun notifyCellAdded() {
        onPatternListener?.onPatternCellAdded(pattern)
    }

    private fun notifyPatternStarted() {
        onPatternListener?.onPatternStart()
    }

    private fun notifyPatternDetected() {
        onPatternListener?.onPatternDetected(pattern)
    }

    private fun notifyPatternCleared() {
        onPatternListener?.onPatternCleared()
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
        pattern.clear()
        clearPatternDrawLookup()
        patternDisplayMode = DisplayMode.Correct
        invalidate()
    }

    /**
     * Clear the pattern lookup table.
     */
    private fun clearPatternDrawLookup() {
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                patternDrawLookup[i][j] = false
            }
        }
    }

    /**
     * Disable input (for instance when displaying a message that will
     * timeout so user doesn't get view into messy state).
     */
    fun disableInput() {
        inputEnabled = false
    }

    /**
     * Enable input.
     */
    fun enableInput() {
        inputEnabled = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = w - paddingLeft - paddingRight
        squareWidth = width / 3.0f

        val height = h - paddingTop - paddingBottom
        squareHeight = height / 3.0f
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
        val cell = checkForNewHit(x, y) ?: return null

        // check for gaps in existing pattern
        if (pattern.isNotEmpty()) {
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

            val fillInGapCell = Cell.of(fillInRow, fillInColumn)
            if (!patternDrawLookup[fillInGapCell.row][fillInGapCell.column]) {
                addCellToPattern(fillInGapCell)
            }
        }
        addCellToPattern(cell)
        if (isTactileFeedbackEnabled) {
            // Honours the system touch-feedback switch (no FLAG_IGNORE_GLOBAL_SETTING).
            performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        }
        return cell
    }

    private fun addCellToPattern(newCell: Cell) {
        patternDrawLookup[newCell.row][newCell.column] = true
        pattern.add(newCell)
        if (!isInStealthMode) {
            startCellActivatedAnimation(newCell)
        }
        notifyCellAdded()
    }

    private fun startCellActivatedAnimation(cell: Cell) {
        val cellState = cellStates[cell.row][cell.column]
        startSizeAnimation(
            dotSize.toFloat(), dotSizeActivated.toFloat(), 96L, linearOutSlowInInterpolator,
            cellState,
        ) {
            startSizeAnimation(
                dotSizeActivated.toFloat(), dotSize.toFloat(), 192L, fastOutSlowInInterpolator,
                cellState, null,
            )
        }
        startLineEndAnimation(
            cellState, inProgressX, inProgressY,
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
        valueAnimator.interpolator = fastOutSlowInInterpolator
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

        if (patternDrawLookup[rowHit][columnHit]) {
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
        val hitSize = squareHeight * HIT_FACTOR

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
        val hitSize = squareWidth * HIT_FACTOR

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
        if (!inputEnabled || !isEnabled) {
            return false
        }

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handleActionDown(event)
                true
            }
            MotionEvent.ACTION_UP -> {
                handleActionUp()
                performClick()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                handleActionMove(event)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (patternInProgress) {
                    patternInProgress = false
                    resetPattern()
                    notifyPatternCleared()
                }
                true
            }
            else -> false
        }
    }

    private fun handleActionMove(event: MotionEvent) {
        // Handle all recent motion events so we don't skip any cells even when the device
        // is busy...
        val radius = pathWidth.toFloat()
        val historySize = event.historySize
        tmpInvalidateRect.setEmpty()
        var invalidateNow = false
        for (i in 0 until historySize + 1) {
            val x = if (i < historySize) event.getHistoricalX(i) else event.x
            val y = if (i < historySize) event.getHistoricalY(i) else event.y
            val hitCell = detectAndAddHit(x, y)
            val patternSize = pattern.size
            if (hitCell != null && patternSize == 1) {
                patternInProgress = true
                notifyPatternStarted()
            }
            // note current x and y for rubber banding of in progress patterns
            val dx = Math.abs(x - inProgressX)
            val dy = Math.abs(y - inProgressY)
            if (dx > DRAG_THRESHHOLD || dy > DRAG_THRESHHOLD) {
                invalidateNow = true
            }

            if (patternInProgress && patternSize > 0) {
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
                    val width = squareWidth * 0.5f
                    val height = squareHeight * 0.5f
                    val hitCellCenterX = getCenterXForColumn(hitCell.column)
                    val hitCellCenterY = getCenterYForRow(hitCell.row)

                    left = Math.min(hitCellCenterX - width, left)
                    right = Math.max(hitCellCenterX + width, right)
                    top = Math.min(hitCellCenterY - height, top)
                    bottom = Math.max(hitCellCenterY + height, bottom)
                }

                // Invalidate between the pattern's last cell and the previous location
                tmpInvalidateRect.union(
                    Math.round(left), Math.round(top),
                    Math.round(right), Math.round(bottom),
                )
            }
        }
        inProgressX = event.x
        inProgressY = event.y

        // To save updates, we only invalidate if the user moved beyond a certain amount.
        if (invalidateNow) {
            invalidateRect.union(tmpInvalidateRect)
            @Suppress("DEPRECATION")
            invalidate(invalidateRect)
            invalidateRect.set(tmpInvalidateRect)
        }
    }

    private fun handleActionUp() {
        // report pattern detected
        if (!pattern.isEmpty()) {
            patternInProgress = false
            cancelLineAnimations()
            notifyPatternDetected()
            invalidate()
        }
    }

    private fun cancelLineAnimations() {
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                val state = cellStates[i][j]
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
            patternInProgress = true
            patternDisplayMode = DisplayMode.Correct
            notifyPatternStarted()
        } else if (patternInProgress) {
            patternInProgress = false
            notifyPatternCleared()
        }
        if (hitCell != null) {
            val startX = getCenterXForColumn(hitCell.column)
            val startY = getCenterYForRow(hitCell.row)

            val widthOffset = squareWidth / 2f
            val heightOffset = squareHeight / 2f

            @Suppress("DEPRECATION")
            invalidate(
                (startX - widthOffset).toInt(), (startY - heightOffset).toInt(),
                (startX + widthOffset).toInt(), (startY + heightOffset).toInt(),
            )
        }
        inProgressX = x
        inProgressY = y
    }

    private fun getCenterXForColumn(column: Int): Float {
        return paddingLeft + column * squareWidth + squareWidth / 2f
    }

    private fun getCenterYForRow(row: Int): Float {
        return paddingTop + row * squareHeight + squareHeight / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val count = pattern.size

        if (patternDisplayMode == DisplayMode.Animate) {
            updateAnimationFrame(count)
        }

        currentPath.rewind()

        drawCircles(canvas)

        // UPSTREAM-LEGACY (AOSP): path should be cached per cell hit-detection
        // only the last segment of the path should be computed here
        // draw the path of the pattern (unless we are in stealth mode)
        if (!isInStealthMode) {
            drawPatternPath(canvas, count)
        }
    }

    /**
     * Animate mode: picks the circles shown at this point of the cycle and
     * moves the in-progress point along the ghosting line.
     */
    private fun updateAnimationFrame(count: Int) {
        // figure out which circles to draw

        // + 1 so we pause on complete pattern
        val oneCycle = (count + 1) * MILLIS_PER_CIRCLE_ANIMATING
        val spotInCycle = (SystemClock.elapsedRealtime() - animatingPeriodStart).toInt() % oneCycle
        val numCircles = spotInCycle / MILLIS_PER_CIRCLE_ANIMATING

        clearPatternDrawLookup()
        for (i in 0 until numCircles) {
            val cell = pattern[i]
            patternDrawLookup[cell.row][cell.column] = true
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
            inProgressX = centerX + dx
            inProgressY = centerY + dy
        }
        // UPSTREAM-LEGACY (AOSP): potential infinite loop in pattern detection
        invalidate()
    }

    private fun drawCircles(canvas: Canvas) {
        for (i in 0 until 3) {
            val centerY = getCenterYForRow(i)
            for (j in 0 until 3) {
                val cellState = cellStates[i][j]
                val centerX = getCenterXForColumn(j)
                val size = cellState.size * cellState.scale
                val translationY = cellState.translateY
                drawCircle(
                    canvas, centerX.toInt().toFloat(), centerY.toInt() + translationY,
                    size, patternDrawLookup[i][j], cellState.alpha,
                )
            }
        }
    }

    private fun drawPatternPath(canvas: Canvas, count: Int) {
        pathPaint.color = getCurrentColor(true /* partOfPattern */)

        var anyCircles = false
        var lastX = 0f
        var lastY = 0f
        for (i in 0 until count) {
            val cell = pattern[i]

            // only draw the part of the pattern stored in
            // the lookup table (this is only different in the case
            // of animation).
            if (!patternDrawLookup[cell.row][cell.column]) {
                break
            }
            anyCircles = true

            val centerX = getCenterXForColumn(cell.column)
            val centerY = getCenterYForRow(cell.row)
            if (i != 0) {
                val state = cellStates[cell.row][cell.column]
                currentPath.rewind()
                currentPath.moveTo(lastX, lastY)
                if (state.lineEndX != Float.MIN_VALUE && state.lineEndY != Float.MIN_VALUE) {
                    currentPath.lineTo(state.lineEndX, state.lineEndY)
                } else {
                    currentPath.lineTo(centerX, centerY)
                }
                canvas.drawPath(currentPath, pathPaint)
            }
            lastX = centerX
            lastY = centerY
        }

        // draw last in progress section
        if ((patternInProgress || patternDisplayMode == DisplayMode.Animate) && anyCircles) {
            currentPath.rewind()
            currentPath.moveTo(lastX, lastY)
            currentPath.lineTo(inProgressX, inProgressY)

            pathPaint.alpha = (calculateLastSegmentAlpha(inProgressX, inProgressY, lastX, lastY) * 255f).toInt()
            canvas.drawPath(currentPath, pathPaint)
        }
    }

    private fun calculateLastSegmentAlpha(x: Float, y: Float, lastX: Float, lastY: Float): Float {
        val diffX = x - lastX
        val diffY = y - lastY
        val dist = Math.sqrt((diffX * diffX + diffY * diffY).toDouble()).toFloat()
        val frac = dist / squareWidth
        return Math.min(1f, Math.max(0f, (frac - 0.3f) * 4f))
    }

    private fun getCurrentColor(partOfPattern: Boolean): Int {
        if (!partOfPattern || isInStealthMode || patternInProgress) {
            // unselected circle
            return regularColor
        }
        return when (patternDisplayMode) {
            // the pattern is wrong
            DisplayMode.Wrong -> errorColor
            DisplayMode.Correct, DisplayMode.Animate -> successColor
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
        paint.color = getCurrentColor(partOfPattern)
        paint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(centerX, centerY, size / 2, paint)
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        return SavedState(
            superState,
            LockPatternUtils.patternToString(pattern),
            patternDisplayMode.ordinal,
            inputEnabled, isInStealthMode, isTactileFeedbackEnabled,
        )
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val ss = state as SavedState
        super.onRestoreInstanceState(ss.superState)
        setPattern(
            DisplayMode.Correct,
            LockPatternUtils.stringToPattern(ss.serializedPattern),
        )
        patternDisplayMode = DisplayMode.entries[ss.displayMode]
        inputEnabled = ss.isInputEnabled
        isInStealthMode = ss.isInStealthMode
        isTactileFeedbackEnabled = ss.isTactileFeedbackEnabled
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

        private const val HIT_FACTOR = 0.6f

        /**
         * This can be used to avoid updating the display for very small motions or noisy panels.
         * It didn't seem to have much impact on the devices tested, so currently set to 0.
         */
        private const val DRAG_THRESHHOLD = 0.0f
    }
}
