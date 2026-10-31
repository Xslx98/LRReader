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
    private val mHelper: Helper,
    private val mSearchBar: View,
    vararg recyclerViews: RecyclerView,
) : RecyclerView.OnScrollListener() {

    private var mShow = false
    private var mSearchBarMoveAnimator: ValueAnimator? = null

    init {
        for (recyclerView in recyclerViews) {
            recyclerView.addOnScrollListener(this)
        }
    }

    fun cancelAnimation() {
        mSearchBarMoveAnimator?.cancel()
    }

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE && mHelper.isValidView(recyclerView)) {
            returnSearchBarPosition()
        }
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (mHelper.isValidView(recyclerView)) {
            val oldBottom = ViewUtils.getY2(mSearchBar).toInt()
            val offsetYStep = MathUtils.clamp(-dy, -oldBottom, -mSearchBar.translationY.toInt())
            if (offsetYStep != 0) {
                ViewUtils.translationYBy(mSearchBar, offsetYStep.toFloat())
            }
        }
    }

    fun returnSearchBarPosition() {
        returnSearchBarPosition(true)
    }

    fun returnSearchBarPosition(animation: Boolean) {
        if (mSearchBar.height == 0) {
            // Layout not called
            return
        }

        val show: Boolean
        if (mHelper.forceShowSearchBar()) {
            show = true
        } else {
            val recyclerView = mHelper.getValidRecyclerView() ?: return
            show = if (!recyclerView.isShown) {
                true
            } else if (recyclerView.computeVerticalScrollOffset() < mSearchBar.bottom) {
                true
            } else {
                ViewUtils.getY2(mSearchBar).toInt() > mSearchBar.height / 2
            }
        }

        val offset = if (show) {
            -mSearchBar.translationY.toInt()
        } else {
            -ViewUtils.getY2(mSearchBar).toInt()
        }

        if (offset == 0) {
            // No need to scroll
            return
        }

        if (animation) {
            val animator = mSearchBarMoveAnimator
            if (animator != null) {
                if (mShow == show) {
                    // The same target, no need to do animation
                    return
                } else {
                    // Cancel it
                    animator.cancel()
                    mSearchBarMoveAnimator = null
                }
            }

            mShow = show
            val va = ValueAnimator.ofInt(0, offset)
            va.duration = ANIMATE_TIME
            va.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mSearchBarMoveAnimator = null
                }
            })
            va.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
                var lastValue = 0

                override fun onAnimationUpdate(animation: ValueAnimator) {
                    val value = animation.animatedValue as Int
                    val offsetStep = value - lastValue
                    lastValue = value
                    ViewUtils.translationYBy(mSearchBar, offsetStep.toFloat())
                }
            })
            mSearchBarMoveAnimator = va
            va.start()
        } else {
            mSearchBarMoveAnimator?.cancel()
            ViewUtils.translationYBy(mSearchBar, offset.toFloat())
        }
    }

    fun showSearchBar() {
        showSearchBar(true)
    }

    fun showSearchBar(animation: Boolean) {
        if (mSearchBar.height == 0) {
            // Layout not called
            return
        }

        val offset = -mSearchBar.translationY.toInt()

        if (offset == 0) {
            // No need to scroll
            return
        }

        if (animation) {
            val animator = mSearchBarMoveAnimator
            if (animator != null) {
                if (mShow) {
                    // The same target, no need to do animation
                    return
                } else {
                    // Cancel it
                    animator.cancel()
                    mSearchBarMoveAnimator = null
                }
            }

            mShow = true
            val va = ValueAnimator.ofInt(0, offset)
            va.duration = ANIMATE_TIME
            va.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mSearchBarMoveAnimator = null
                }
            })
            va.addUpdateListener(object : ValueAnimator.AnimatorUpdateListener {
                var lastValue = 0

                override fun onAnimationUpdate(animation: ValueAnimator) {
                    val value = animation.animatedValue as Int
                    val offsetStep = value - lastValue
                    lastValue = value
                    ViewUtils.translationYBy(mSearchBar, offsetStep.toFloat())
                }
            })
            mSearchBarMoveAnimator = va
            va.start()
        } else {
            mSearchBarMoveAnimator?.cancel()
            ViewUtils.translationYBy(mSearchBar, offset.toFloat())
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
