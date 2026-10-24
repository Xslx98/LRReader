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

package com.lanraragi.reader.widget

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import androidx.core.animation.doOnEnd

/**
 * Shows exactly one of [views] at a time (content / progress / tip and the like),
 * cross-fading between them. Starts on the first view, without animation.
 */
class ViewTransition(vararg views: View?) {

    private val views: Array<View>

    var shownViewIndex = -1
        private set

    var onShowViewListener: OnShowViewListener? = null

    private var hideAnimator: Animator? = null
    private var showAnimator: Animator? = null

    init {
        check(views.size >= 2) { "You must pass view to ViewTransition" }
        this.views = Array(views.size) { i ->
            checkNotNull(views[i]) { "Any View pass to ViewTransition must not be null" }
        }
        showView(0, false)
    }

    /** Returns false when [index] is already shown. */
    @JvmOverloads
    fun showView(index: Int, animation: Boolean = true): Boolean {
        if (index !in views.indices) {
            throw IndexOutOfBoundsException(
                "Only ${views.size} view(s) in the ViewTransition, but attempt to show $index",
            )
        }
        if (shownViewIndex == index) return false

        val old = shownViewIndex
        shownViewIndex = index
        hideAnimator?.cancel()
        showAnimator?.cancel()

        if (animation) {
            views.forEachIndexed { i, v -> if (i != old && i != index) v.hide() }
            startAnimations(views[old], views[index])
        } else {
            views.forEachIndexed { i, v ->
                if (i == index) {
                    v.alpha = 1f
                    v.visibility = View.VISIBLE
                } else {
                    v.hide()
                }
            }
        }
        onShowViewListener?.onShowView(views[old], views[index])
        return true
    }

    private fun View.hide() {
        alpha = 0f
        visibility = View.GONE
    }

    private fun startAnimations(hiddenView: View, shownView: View) {
        hideAnimator = ObjectAnimator.ofFloat(hiddenView, View.ALPHA, 0f).apply {
            duration = ANIMATE_TIME
            doOnEnd {
                hiddenView.visibility = View.GONE
                hideAnimator = null
            }
            start()
        }
        shownView.visibility = View.VISIBLE
        showAnimator = ObjectAnimator.ofFloat(shownView, View.ALPHA, 1f).apply {
            duration = ANIMATE_TIME
            doOnEnd { showAnimator = null }
            start()
        }
    }

    fun interface OnShowViewListener {
        fun onShowView(hiddenView: View, shownView: View)
    }

    private companion object {
        const val ANIMATE_TIME = 300L
    }
}
