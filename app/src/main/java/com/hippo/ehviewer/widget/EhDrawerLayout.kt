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

package com.hippo.ehviewer.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import com.google.android.material.snackbar.Snackbar
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.ehviewer.R
import com.hippo.lib.yorozuya.AnimationUtils

@CoordinatorLayout.DefaultBehavior(EhDrawerLayout.Behavior::class)
class EhDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : DrawerLayout(context, attrs, defStyle) {

    private var mAboveSnackViewList: MutableList<View>? = null

    fun addAboveSnackView(view: View) {
        if (mAboveSnackViewList == null) {
            mAboveSnackViewList = ArrayList()
        }
        mAboveSnackViewList!!.add(view)
    }

    fun removeAboveSnackView(view: View) {
        mAboveSnackViewList?.remove(view)
    }

    fun getAboveSnackViewCount(): Int {
        return mAboveSnackViewList?.size ?: 0
    }

    fun getAboveSnackViewAt(index: Int): View? {
        val list = mAboveSnackViewList ?: return null
        return if (index < 0 || index >= list.size) null else list[index]
    }

    @SuppressLint("RestrictedApi")
    class Behavior : CoordinatorLayout.Behavior<EhDrawerLayout>() {

        override fun layoutDependsOn(
            parent: CoordinatorLayout,
            child: EhDrawerLayout,
            dependency: View
        ): Boolean {
            return SNACKBAR_BEHAVIOR_ENABLED && dependency is Snackbar.SnackbarLayout
        }

        override fun onDependentViewChanged(
            parent: CoordinatorLayout,
            child: EhDrawerLayout,
            dependency: View
        ): Boolean {
            if (dependency is Snackbar.SnackbarLayout) {
                for (i in 0 until child.getAboveSnackViewCount()) {
                    val view = child.getAboveSnackViewAt(i)
                    updateChildTranslationForSnackbar(parent, child, view!!)
                }
            }
            return false
        }

        private fun updateChildTranslationForSnackbar(
            parent: CoordinatorLayout,
            view: EhDrawerLayout,
            child: View
        ) {
            val targetTransY = getChildTranslationYForSnackbar(parent, view)
            var childTranslationY = 0.0f
            var obj: Any? = child.getTag(R.id.fab_translation_y)
            if (obj is Float) {
                childTranslationY = obj
            }
            if (childTranslationY == targetTransY) {
                return
            }

            var fabTranslationYAnimator: ValueAnimator? = null
            obj = child.getTag(R.id.fab_translation_y_animator)
            if (obj is ValueAnimator) {
                fabTranslationYAnimator = obj
            }
            if (fabTranslationYAnimator != null && fabTranslationYAnimator.isRunning) {
                fabTranslationYAnimator.cancel()
            }

            val currentTransY = ViewCompat.getTranslationY(child)

            if (child.isShown && Math.abs(currentTransY - targetTransY) > child.height * 0.667f) {
                if (fabTranslationYAnimator == null) {
                    fabTranslationYAnimator = ValueAnimator.ofFloat()
                    fabTranslationYAnimator.interpolator = AnimationUtils.FAST_SLOW_INTERPOLATOR
                    fabTranslationYAnimator.addUpdateListener { animation ->
                        ViewCompat.setTranslationY(child, animation.animatedValue as Float)
                    }
                    child.setTag(R.id.fab_translation_y_animator, fabTranslationYAnimator)
                }
                fabTranslationYAnimator.setFloatValues(currentTransY, targetTransY)
                fabTranslationYAnimator.start()
            } else {
                ViewCompat.setTranslationY(child, targetTransY)
            }

            child.setTag(R.id.fab_translation_y, targetTransY)
        }

        private fun getChildTranslationYForSnackbar(
            parent: CoordinatorLayout,
            child: EhDrawerLayout
        ): Float {
            var minOffset = 0f
            val dependencies = parent.getDependencies(child)
            for (i in dependencies.indices) {
                val view = dependencies[i]
                if (view is Snackbar.SnackbarLayout && parent.doViewsOverlap(child, view)) {
                    minOffset = minOf(minOffset, ViewCompat.getTranslationY(view) - view.height)
                }
            }
            return minOffset
        }

        companion object {
            private val SNACKBAR_BEHAVIOR_ENABLED = Build.VERSION.SDK_INT >= 11
        }
    }
}
