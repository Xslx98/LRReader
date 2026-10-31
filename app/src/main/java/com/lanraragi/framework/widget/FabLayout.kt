/*
 * Copyright (C) 2015 Hippo Seven
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

package com.lanraragi.framework.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import androidx.core.os.BundleCompat
import androidx.core.view.isGone
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.lanraragi.reader.R
import com.lanraragi.reader.util.AnimationUtils
import com.lanraragi.reader.util.AssertUtils
import kotlin.math.max

class FabLayout : ViewGroup, View.OnClickListener {

    private var fabSize = 0
    private var fabMiniSize = 0
    private var intervalPrimary = 0
    private var intervalSecondary = 0

    private var expandedState = true
    private var mainFabCenterY = -1f

    var onExpandListener: OnExpandListener? = null

    var onClickFabListener: OnClickFabListener? = null
        set(listener) {
            field = listener
            if (listener != null) {
                for (i in 0 until childCount) {
                    getChildAt(i).setOnClickListener(this)
                }
            } else {
                for (i in 0 until childCount) {
                    getChildAt(i).isClickable = false
                }
            }
        }

    var hidePrimaryFab = false
        set(hidePrimaryFab) {
            if (field != hidePrimaryFab) {
                field = hidePrimaryFab
                val count = childCount
                if (!expandedState && count > 0) {
                    getChildAt(count - 1).visibility = if (hidePrimaryFab) INVISIBLE else VISIBLE
                }
            }
        }

    var autoCancel = true
        set(autoCancel) {
            if (field != autoCancel) {
                field = autoCancel

                if (expandedState) {
                    if (autoCancel) {
                        setOnClickListener(this)
                    } else {
                        isClickable = false
                    }
                }
            }
        }

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    private fun init(context: Context) {
        isSoundEffectsEnabled = false
        clipToPadding = false
        fabSize = context.resources.getDimensionPixelOffset(R.dimen.fab_size)
        fabMiniSize = context.resources.getDimensionPixelOffset(R.dimen.fab_min_size)
        intervalPrimary = context.resources.getDimensionPixelOffset(R.dimen.fab_layout_primary_margin)
        intervalSecondary = context.resources.getDimensionPixelOffset(R.dimen.fab_layout_secondary_margin)
    }

    override fun addView(child: View, index: Int, params: LayoutParams?) {
        check(child is FloatingActionButton) {
            "FloatingActionBarLayout should only contain FloatingActionButton, but try to add " + child.javaClass.name
        }
        super.addView(child, index, params)
    }

    /** The last child; there is always one once the layout is inflated. */
    val primaryFab: FloatingActionButton
        get() = getChildAt(childCount - 1) as FloatingActionButton

    val secondaryFabCount: Int
        get() = max(0, childCount - 1)

    fun getSecondaryFabAt(index: Int): FloatingActionButton? {
        if (index < 0 || index >= secondaryFabCount) {
            return null
        }
        return getChildAt(index) as FloatingActionButton
    }

    fun setSecondaryFabVisibilityAt(index: Int, visible: Boolean) {
        val fab: View? = getSecondaryFabAt(index)
        if (fab != null) {
            if (visible && fab.isGone) {
                fab.animate().cancel()
                fab.visibility = if (expandedState) View.VISIBLE else View.INVISIBLE
            } else if (!visible && !fab.isGone) {
                fab.animate().cancel()
                fab.visibility = View.GONE
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        AssertUtils.assertEquals(
            "Measure mode must be MeasureSpec.EXACTLY",
            MeasureSpec.getMode(widthMeasureSpec), MeasureSpec.EXACTLY,
        )
        AssertUtils.assertEquals(
            "Measure mode must be MeasureSpec.EXACTLY",
            MeasureSpec.getMode(heightMeasureSpec), MeasureSpec.EXACTLY,
        )

        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
            width - paddingLeft - paddingRight, MeasureSpec.AT_MOST,
        )
        val childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
            height - paddingTop - paddingBottom, MeasureSpec.AT_MOST,
        )
        measureChildren(childWidthMeasureSpec, childHeightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    // For pre-L, FloatActionButton use padding to show shadow, so its position looks wrong.
    // We use it default size to make it position right
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var centerX = 0
        var bottom = measuredHeight - paddingBottom
        val count = childCount
        var i = count
        while (--i >= 0) {
            val child = getChildAt(i)
            if (child.isGone) {
                continue
            }

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val layoutBottom: Int
            val layoutRight: Int
            if (i == count - 1) {
                layoutBottom = bottom + (childHeight - fabSize) / 2
                layoutRight = measuredWidth - paddingRight + (childWidth - fabSize) / 2
                bottom -= fabSize + intervalPrimary
                centerX = layoutRight - childWidth / 2
                mainFabCenterY = layoutBottom - childHeight / 2f
            } else {
                layoutBottom = bottom + (childHeight - fabMiniSize) / 2
                layoutRight = centerX + childWidth / 2
                bottom -= fabMiniSize + intervalSecondary
            }
            child.layout(layoutRight - childWidth, layoutBottom - childHeight, layoutRight, layoutBottom)
        }
    }

    fun toggle() {
        isExpanded = !expandedState
    }

    var isExpanded: Boolean
        get() = expandedState
        set(expanded) {
            setExpanded(expanded, true)
        }

    fun setExpanded(expanded: Boolean, animation: Boolean) {
        if (expandedState == expanded) {
            return
        }
        expandedState = expanded

        if (autoCancel) {
            if (expanded) {
                setOnClickListener(this)
            } else {
                isClickable = false
            }
        }

        val count = childCount
        if (count > 0) {
            if (mainFabCenterY == -1f || !animation) {
                // It is before first onLayout
                setFabsVisibility(count, expanded)
            } else {
                animateFabs(count, expanded)
            }
        }

        onExpandListener?.onExpand(expanded)
    }

    private fun setFabsVisibility(count: Int, expanded: Boolean) {
        val checkCount = if (hidePrimaryFab) count else count - 1
        for (i in 0 until checkCount) {
            val child = getChildAt(i)
            if (child.isGone) {
                continue
            }
            child.visibility = if (expanded) View.VISIBLE else View.INVISIBLE
            if (expanded) {
                child.alpha = 1f
            }
        }
    }

    private fun animateFabs(count: Int, expanded: Boolean) {
        if (hidePrimaryFab) {
            setPrimaryFabAnimation(getChildAt(count - 1), expanded, !expanded)
        }

        for (i in 0 until count - 1) {
            val child = getChildAt(i)
            if (child.isGone) {
                continue
            }
            setSecondaryFabAnimation(child, expanded, expanded)
        }
    }

    private fun setPrimaryFabAnimation(child: View, expanded: Boolean, delay: Boolean) {
        val startRotation: Float
        val endRotation: Float
        val startScale: Float
        val endScale: Float
        val interpolator: Interpolator
        if (expanded) {
            startRotation = -45.0f
            endRotation = 0.0f
            startScale = 0.0f
            endScale = 1.0f
            interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
        } else {
            startRotation = 0.0f
            endRotation = 0.0f
            startScale = 1.0f
            endScale = 0.0f
            interpolator = AnimationUtils.SLOW_FAST_INTERPOLATOR
        }

        child.scaleX = startScale
        child.scaleY = startScale
        child.rotation = startRotation
        child.animate()
            .scaleX(endScale)
            .scaleY(endScale)
            .rotation(endRotation)
            .setStartDelay(if (delay) ANIMATE_TIME else 0L)
            .setDuration(ANIMATE_TIME)
            .setInterpolator(interpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (expanded) {
                        child.visibility = View.VISIBLE
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!expanded) {
                        child.visibility = View.INVISIBLE
                    }
                }
            }).start()
    }

    private fun setSecondaryFabAnimation(child: View, expanded: Boolean, delay: Boolean) {
        val startTranslationY: Float
        val endTranslationY: Float
        val startAlpha: Float
        val endAlpha: Float
        val interpolator: Interpolator
        if (expanded) {
            startTranslationY = mainFabCenterY - (child.top + child.height / 2)
            endTranslationY = 0f
            startAlpha = 0f
            endAlpha = 1f
            interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
        } else {
            startTranslationY = 0f
            endTranslationY = mainFabCenterY - (child.top + child.height / 2)
            startAlpha = 1f
            endAlpha = 0f
            interpolator = AnimationUtils.SLOW_FAST_INTERPOLATOR
        }

        child.alpha = startAlpha
        child.translationY = startTranslationY
        child.animate()
            .alpha(endAlpha)
            .translationY(endTranslationY)
            .setStartDelay(if (delay) ANIMATE_TIME else 0L)
            .setDuration(ANIMATE_TIME)
            .setInterpolator(interpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (expanded) {
                        child.visibility = View.VISIBLE
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!expanded) {
                        child.visibility = View.INVISIBLE
                    }
                }
            }).start()
    }

    override fun onClick(v: View) {
        val listener = onClickFabListener
        if (this === v) {
            isExpanded = false
        } else if (listener != null) {
            val position = indexOfChild(v)
            if (position == childCount - 1) {
                listener.onClickPrimaryFab(this, v as FloatingActionButton)
            } else if (position >= 0 && expandedState) {
                listener.onClickSecondaryFab(this, v as FloatingActionButton, position)
            }
        }
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        // Don't dispatch it to children
    }

    override fun onSaveInstanceState(): Parcelable {
        val state = Bundle()
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState())
        state.putBoolean(STATE_KEY_AUTO_CANCEL, autoCancel)
        state.putBoolean(STATE_KEY_EXPANDED, expandedState)
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(BundleCompat.getParcelable(state, STATE_KEY_SUPER, Parcelable::class.java))
            autoCancel = state.getBoolean(STATE_KEY_AUTO_CANCEL)
            setExpanded(state.getBoolean(STATE_KEY_EXPANDED), false)
        }
    }

    fun interface OnExpandListener {
        fun onExpand(expanded: Boolean)
    }

    interface OnClickFabListener {

        fun onClickPrimaryFab(view: FabLayout, fab: FloatingActionButton?)

        fun onClickSecondaryFab(view: FabLayout, fab: FloatingActionButton, position: Int)
    }

    companion object {
        private const val ANIMATE_TIME = 300L

        private const val STATE_KEY_SUPER = "super"
        private const val STATE_KEY_AUTO_CANCEL = "auto_cancel"
        private const val STATE_KEY_EXPANDED = "expanded"
    }
}
