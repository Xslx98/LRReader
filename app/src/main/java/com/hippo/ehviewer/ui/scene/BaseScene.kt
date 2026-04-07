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

package com.hippo.ehviewer.ui.scene

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.scene.SceneFragment
import com.hippo.util.AppHelper

abstract class BaseScene : SceneFragment() {

    private var mThemeContext: Context? = null

    private var drawerView: View? = null
    private var drawerViewState: SparseArray<Parcelable>? = null

    open fun updateAvatar() {
        val activity = activity
        if (activity is MainActivity) {
            activity.updateProfile()
        }
    }

    open fun addAboveSnackView(view: View) {
        val activity = activity
        if (activity is MainActivity) {
            activity.addAboveSnackView(view)
        }
    }

    open fun removeAboveSnackView(view: View) {
        val activity = activity
        if (activity is MainActivity) {
            activity.removeAboveSnackView(view)
        }
    }

    open fun setDrawerLockMode(lockMode: Int, edgeGravity: Int) {
        val activity = activity
        if (activity is MainActivity) {
            activity.setDrawerLockMode(lockMode, edgeGravity)
        }
    }

    open fun openDrawer(drawerGravity: Int) {
        val activity = activity
        if (activity is MainActivity) {
            activity.openDrawer(drawerGravity)
        }
    }

    open fun closeDrawer(drawerGravity: Int) {
        val activity = activity
        if (activity is MainActivity) {
            activity.closeDrawer(drawerGravity)
        }
    }

    open fun toggleDrawer(drawerGravity: Int) {
        val activity = activity
        if (activity is MainActivity) {
            activity.toggleDrawer(drawerGravity)
        }
    }

    open fun setDrawerGestureBlocker(gestureBlocker: DrawerLayout.GestureBlocker?) {
        val activity = activity
        if (activity is MainActivity) {
            activity.setDrawerGestureBlocker(gestureBlocker)
        }
    }

    open fun isDrawersVisible(): Boolean {
        val activity = activity
        return if (activity is MainActivity) {
            activity.isDrawersVisible
        } else {
            false
        }
    }

    /**
     * @param resId 0 for clear
     */
    open fun setNavCheckedItem(@IdRes resId: Int) {
        val activity = activity
        if (activity is MainActivity) {
            activity.setNavCheckedItem(resId)
        }
    }

    open fun showTip(message: CharSequence, length: Int) {
        val activity = activity
        if (activity is MainActivity) {
            activity.showTip(message, length)
        }
    }

    open fun showTip(@StringRes id: Int, length: Int) {
        val activity = activity
        if (activity is MainActivity) {
            activity.showTip(id, length)
        }
    }

    open fun needShowLeftDrawer(): Boolean {
        return true
    }

    open fun getNavCheckedItem(): Int {
        return 0
    }

    fun createDrawerView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        drawerView = onCreateDrawerView(inflater, container, savedInstanceState)

        if (drawerView != null) {
            var saved = drawerViewState
            if (saved == null && savedInstanceState != null) {
                saved = savedInstanceState.getSparseParcelableArray(KEY_DRAWER_VIEW_STATE)
            }
            if (saved != null) {
                drawerView!!.restoreHierarchyState(saved)
            }
        }

        return drawerView
    }

    open fun onCreateDrawerView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return null
    }

    fun destroyDrawerView() {
        if (drawerView != null) {
            drawerViewState = SparseArray()
            drawerView!!.saveHierarchyState(drawerViewState)
        }

        onDestroyDrawerView()

        drawerView = null
    }

    open fun onDestroyDrawerView() {
    }

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return onCreateView2(LayoutInflater.from(ehContext), container, savedInstanceState)
    }

    open fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return null
    }

    @SuppressLint("RtlHardcoded")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Update left drawer locked state
        if (needShowLeftDrawer()) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT)
        } else {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
        }

        // Update nav checked item
        setNavCheckedItem(getNavCheckedItem())

        // Hide soft ime
        AppHelper.hideSoftInput(requireActivity())
    }

    open fun createThemeContext(@StyleRes style: Int) {
        mThemeContext = ContextThemeWrapper(context, style)
    }

    open fun destroyThemeContext() {
        mThemeContext = null
    }

    open val ehContext: Context?
        get() = mThemeContext ?: super.getContext()

    // Keep Java-compatible getter name
    @Suppress("unused")
    open fun getEHContext(): Context? = ehContext

    open val resources2: Resources?
        get() = ehContext?.resources

    open val activity2: MainActivity?
        get() {
            val activity = activity
            return if (activity is MainActivity) activity else null
        }

    open val layoutInflater2: LayoutInflater
        get() {
            var context: Context? = ehContext
            if (context == null) {
                context = getContext()
            }
            return LayoutInflater.from(context) ?: layoutInflater
        }

    open fun hideSoftInput() {
        val activity = activity
        if (activity != null) {
            AppHelper.hideSoftInput(activity)
        }
    }

    open fun showSoftInput(view: View?) {
        val activity = activity
        if (activity != null && view != null) {
            AppHelper.showSoftInput(activity, view, true)
        }
    }

    override fun onResume() {
        super.onResume()
        Analytics.onSceneView(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (drawerView != null) {
            drawerViewState = SparseArray()
            drawerView!!.saveHierarchyState(drawerViewState)
            outState.putSparseParcelableArray(KEY_DRAWER_VIEW_STATE, drawerViewState)
        }
    }

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @JvmField
        val KEY_DRAWER_VIEW_STATE = "com.hippo.ehviewer.ui.scene.BaseScene:DRAWER_VIEW_STATE"
    }
}
