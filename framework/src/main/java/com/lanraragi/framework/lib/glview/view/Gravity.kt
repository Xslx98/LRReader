package com.lanraragi.framework.lib.glview.view

import androidx.annotation.IntDef

object Gravity {

    @IntDef(POSITION_BEGIN, POSITION_CENTER, POSITION_FINISH)
    @Retention(AnnotationRetention.SOURCE)
    annotation class PositionMode

    @IntDef(HORIZONTAL, VERTICAL)
    @Retention(AnnotationRetention.SOURCE)
    annotation class DirectionMode

    const val NO_GRAVITY = 0x0000

    const val LEFT = 0x0001
    const val TOP = 0x0002
    const val RIGHT = 0x0004
    const val BOTTOM = 0x0008

    const val CENTER_HORIZONTAL = LEFT or RIGHT
    const val CENTER_VERTICAL = TOP or BOTTOM
    const val CENTER = CENTER_VERTICAL or CENTER_HORIZONTAL

    const val HORIZONTAL_MASK = CENTER_HORIZONTAL
    const val VERTICAL_MASK = CENTER_VERTICAL

    const val POSITION_BEGIN = 0
    const val POSITION_CENTER = 1
    const val POSITION_FINISH = 2

    const val HORIZONTAL = 0
    const val VERTICAL = 1

    @JvmStatic
    fun centerHorizontal(gravity: Int): Boolean = (gravity and HORIZONTAL_MASK) == CENTER_HORIZONTAL

    @JvmStatic
    fun right(gravity: Int): Boolean = (gravity and HORIZONTAL_MASK) == RIGHT

    @JvmStatic
    fun left(gravity: Int): Boolean = (gravity and HORIZONTAL_MASK) == LEFT

    @JvmStatic
    fun centerVertical(gravity: Int): Boolean = (gravity and VERTICAL_MASK) == CENTER_VERTICAL

    @JvmStatic
    fun top(gravity: Int): Boolean = (gravity and VERTICAL_MASK) == TOP

    @JvmStatic
    fun bottom(gravity: Int): Boolean = (gravity and VERTICAL_MASK) == BOTTOM

    @JvmStatic
    @PositionMode
    fun getPosition(gravity: Int, @DirectionMode direction: Int): Int = if (direction == HORIZONTAL) {
        when {
            right(gravity) -> POSITION_FINISH
            centerHorizontal(gravity) -> POSITION_CENTER
            else -> POSITION_BEGIN
        }
    } else {
        when {
            bottom(gravity) -> POSITION_FINISH
            centerVertical(gravity) -> POSITION_CENTER
            else -> POSITION_BEGIN
        }
    }
}
