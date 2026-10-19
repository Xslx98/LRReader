package com.lanraragi.reader.ui.scene.gallery.detail

import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import com.hippo.drawerlayout.DrawerLayout

/**
 * Keeps the drawer's edge swipe from stealing a horizontal drag that starts
 * inside [area] (the detail page's scrollable action row) while no drawer
 * is showing.
 */
class ScrollAreaGestureBlocker(
    private val area: View,
    private val isDrawersVisible: () -> Boolean,
) : DrawerLayout.GestureBlocker {

    private fun transformPointToViewLocal(point: IntArray, child: View) {
        var currentChild = child
        var viewParent: ViewParent? = currentChild.parent

        while (viewParent is View) {
            val parentView = viewParent
            point[0] += parentView.scrollX - currentChild.left
            point[1] += parentView.scrollY - currentChild.top

            if (parentView is DrawerLayout) {
                break
            }

            currentChild = parentView
            viewParent = currentChild.parent
        }
    }

    override fun shouldBlockGesture(ev: MotionEvent): Boolean {
        val point = intArrayOf(ev.x.toInt(), ev.y.toInt())
        transformPointToViewLocal(point, area)
        return !isDrawersVisible() &&
            point[0] > 0 && point[0] < area.width &&
            point[1] > 0 && point[1] < area.height
    }
}
