/*
 * Copyright 2016 Hippo Seven
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

package com.lanraragi.framework.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.util.MathUtils
import com.lanraragi.reader.util.ViewUtils

class SearchBarMover(
    private val helper: Helper,
    private val searchBar: View,
    vararg recyclerViews: RecyclerView,
) : RecyclerView.OnScrollListener() {

    private var targetShown = false
    private var moveAnimator: ValueAnimator? = null

    init {
        for (recyclerView in recyclerViews) {
            recyclerView.addOnScrollListener(this)
        }
    }

    fun cancelAnimation() {
        moveAnimator?.cancel()
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE && helper.isValidView(recyclerView)) {
            returnSearchBarPosition()
        }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (helper.isValidView(recyclerView)) {
            val oldBottom = ViewUtils.getY2(searchBar).toInt()
            val offsetYStep = MathUtils.clamp(-dy, -oldBottom, -searchBar.translationY.toInt())
            if (offsetYStep != 0) {
                ViewUtils.translationYBy(searchBar, offsetYStep.toFloat())
            }
        }
    }

    fun returnSearchBarPosition() {
        returnSearchBarPosition(true)
    }

    fun returnSearchBarPosition(animation: Boolean) {
        if (searchBar.height == 0) {
            // Layout not called
            return
        }

        val show: Boolean
        if (helper.forceShowSearchBar()) {
            show = true
        } else {
            val recyclerView = helper.getValidRecyclerView() ?: return
            show = if (!recyclerView.isShown) {
                true
            } else if (recyclerView.computeVerticalScrollOffset() < searchBar.bottom) {
                true
            } else {
                ViewUtils.getY2(searchBar).toInt() > searchBar.height / 2
            }
        }

        val offset = if (show) {
            -searchBar.translationY.toInt()
        } else {
            -ViewUtils.getY2(searchBar).toInt()
        }

        if (offset == 0) {
            // No need to scroll
            return
        }

        if (animation) {
            val animator = moveAnimator
            if (animator != null) {
                if (targetShown == show) {
                    // The same target, no need to do animation
                    return
                } else {
                    // Cancel it
                    animator.cancel()
                    moveAnimator = null
                }
            }

            targetShown = show
            val va = ValueAnimator.ofInt(0, offset)
            va.duration = ANIMATE_TIME
            va.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    moveAnimator = null
                }
            })
            va.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
                var lastValue = 0

                override fun onAnimationUpdate(animation: ValueAnimator) {
                    val value = animation.animatedValue as Int
                    val offsetStep = value - lastValue
                    lastValue = value
                    ViewUtils.translationYBy(searchBar, offsetStep.toFloat())
                }
            })
            moveAnimator = va
            va.start()
        } else {
            moveAnimator?.cancel()
            ViewUtils.translationYBy(searchBar, offset.toFloat())
        }
    }

    fun showSearchBar() {
        showSearchBar(true)
    }

    fun showSearchBar(animation: Boolean) {
        if (searchBar.height == 0) {
            // Layout not called
            return
        }

        val offset = -searchBar.translationY.toInt()

        if (offset == 0) {
            // No need to scroll
            return
        }

        if (animation) {
            val animator = moveAnimator
            if (animator != null) {
                if (targetShown) {
                    // The same target, no need to do animation
                    return
                } else {
                    // Cancel it
                    animator.cancel()
                    moveAnimator = null
                }
            }

            targetShown = true
            val va = ValueAnimator.ofInt(0, offset)
            va.duration = ANIMATE_TIME
            va.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    moveAnimator = null
                }
            })
            va.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
                var lastValue = 0

                override fun onAnimationUpdate(animation: ValueAnimator) {
                    val value = animation.animatedValue as Int
                    val offsetStep = value - lastValue
                    lastValue = value
                    ViewUtils.translationYBy(searchBar, offsetStep.toFloat())
                }
            })
            moveAnimator = va
            va.start()
        } else {
            moveAnimator?.cancel()
            ViewUtils.translationYBy(searchBar, offset.toFloat())
        }
    }

    interface Helper {

        fun isValidView(recyclerView: RecyclerView): Boolean

        fun getValidRecyclerView(): RecyclerView?

        fun forceShowSearchBar(): Boolean
    }

    companion object {
        private const val ANIMATE_TIME = 300L
    }
}
