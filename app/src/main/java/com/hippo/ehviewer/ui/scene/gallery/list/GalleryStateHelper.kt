package com.hippo.ehviewer.ui.scene.gallery.list

import android.animation.Animator
import android.view.Gravity
import android.view.View
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawable.DrawerArrowDrawable
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.widget.FabLayout
import com.hippo.widget.SearchBarMover
import com.hippo.view.ViewTransition
import com.hippo.ehviewer.widget.SearchLayout
import com.hippo.lib.yorozuya.AnimationUtils
import com.hippo.lib.yorozuya.SimpleAnimatorListener

/**
 * Manages search state machine and FAB animations for GalleryListScene.
 * Handles the 4-state transitions (NORMAL, SIMPLE_SEARCH, SEARCH, SEARCH_SHOW_LIST)
 * and the FAB show/hide/select animations.
 * Extracted to reduce GalleryListScene's line count.
 */
class GalleryStateHelper(private val callback: Callback) {

    interface Callback {
        fun getSearchBar(): SearchBar?
        fun getSearchBarMover(): SearchBarMover?
        fun getViewTransition(): ViewTransition?
        fun getSearchLayout(): SearchLayout?
        fun getFabLayout(): FabLayout?
        fun getSearchFab(): View?
        fun getActionFabDrawable(): AddDeleteDrawable?
        fun getLeftDrawable(): DrawerArrowDrawable?
        fun getRightDrawable(): AddDeleteDrawable?
        fun setDrawerLockMode(mode: Int, gravity: Int)
    }

    var state = STATE_NORMAL
        private set
    private var mShowActionFab = true

    private val mActionFabAnimatorListener: Animator.AnimatorListener = object : SimpleAnimatorListener() {
        override fun onAnimationEnd(animation: Animator) {
            callback.getFabLayout()?.let { (it.primaryFab as View).visibility = View.INVISIBLE }
        }
    }

    private val mSearchFabAnimatorListener: Animator.AnimatorListener = object : SimpleAnimatorListener() {
        override fun onAnimationEnd(animation: Animator) {
            callback.getSearchFab()?.visibility = View.INVISIBLE
        }
    }

    fun resetShowActionFab() {
        mShowActionFab = true
    }

    fun setState(newState: Int) {
        setState(newState, true)
    }

    fun setState(newState: Int, animation: Boolean) {
        val searchBar = callback.getSearchBar() ?: return
        val searchBarMover = callback.getSearchBarMover() ?: return
        val viewTransition = callback.getViewTransition() ?: return
        val searchLayout = callback.getSearchLayout() ?: return

        if (state != newState) {
            val oldState = state
            state = newState

            when (oldState) {
                STATE_NORMAL -> {
                    when (newState) {
                        STATE_SIMPLE_SEARCH -> {
                            searchBar.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            searchBarMover.returnSearchBarPosition()
                            selectSearchFab(animation)
                        }
                        STATE_SEARCH -> {
                            viewTransition.showView(1, animation)
                            searchLayout.scrollSearchContainerToTop()
                            searchBar.setState(SearchBar.STATE_SEARCH, animation)
                            searchBarMover.returnSearchBarPosition()
                            selectSearchFab(animation)
                        }
                        STATE_SEARCH_SHOW_LIST -> {
                            viewTransition.showView(1, animation)
                            searchLayout.scrollSearchContainerToTop()
                            searchBar.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            searchBarMover.returnSearchBarPosition()
                            selectSearchFab(animation)
                        }
                    }
                }
                STATE_SIMPLE_SEARCH -> {
                    when (newState) {
                        STATE_NORMAL -> {
                            searchBar.setState(SearchBar.STATE_NORMAL, animation)
                            searchBarMover.returnSearchBarPosition()
                            selectActionFab(animation)
                        }
                        STATE_SEARCH -> {
                            viewTransition.showView(1, animation)
                            searchLayout.scrollSearchContainerToTop()
                            searchBar.setState(SearchBar.STATE_SEARCH, animation)
                            searchBarMover.returnSearchBarPosition()
                        }
                        STATE_SEARCH_SHOW_LIST -> {
                            viewTransition.showView(1, animation)
                            searchLayout.scrollSearchContainerToTop()
                            searchBar.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            searchBarMover.returnSearchBarPosition()
                        }
                    }
                }
                STATE_SEARCH -> {
                    when (newState) {
                        STATE_NORMAL -> {
                            viewTransition.showView(0, animation)
                            searchBar.setState(SearchBar.STATE_NORMAL, animation)
                            searchBarMover.returnSearchBarPosition()
                            selectActionFab(animation)
                        }
                        STATE_SIMPLE_SEARCH -> {
                            viewTransition.showView(0, animation)
                            searchBar.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            searchBarMover.returnSearchBarPosition()
                        }
                        STATE_SEARCH_SHOW_LIST -> {
                            searchBar.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            searchBarMover.returnSearchBarPosition()
                        }
                    }
                }
                STATE_SEARCH_SHOW_LIST -> {
                    when (newState) {
                        STATE_NORMAL -> {
                            viewTransition.showView(0, animation)
                            searchBar.setState(SearchBar.STATE_NORMAL, animation)
                            searchBarMover.returnSearchBarPosition()
                            selectActionFab(animation)
                        }
                        STATE_SIMPLE_SEARCH -> {
                            viewTransition.showView(0, animation)
                            searchBar.setState(SearchBar.STATE_SEARCH_LIST, animation)
                            searchBarMover.returnSearchBarPosition()
                        }
                        STATE_SEARCH -> {
                            searchBar.setState(SearchBar.STATE_SEARCH, animation)
                            searchBarMover.returnSearchBarPosition()
                        }
                    }
                }
            }
        }
    }

    fun showActionFab() {
        val fabLayout = callback.getFabLayout() ?: return
        if (state == STATE_NORMAL && !mShowActionFab) {
            mShowActionFab = true
            val fab: View = fabLayout.primaryFab
            fab.visibility = View.VISIBLE
            fab.rotation = -45.0f
            fab.animate().scaleX(1.0f).scaleY(1.0f).rotation(0.0f).setListener(null)
                .setDuration(ANIMATE_TIME).setStartDelay(0L)
                .setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR).start()
        }
    }

    fun hideActionFab() {
        val fabLayout = callback.getFabLayout() ?: return
        if (state == STATE_NORMAL && mShowActionFab) {
            mShowActionFab = false
            val fab: View = fabLayout.primaryFab
            fab.animate().scaleX(0.0f).scaleY(0.0f).setListener(mActionFabAnimatorListener)
                .setDuration(ANIMATE_TIME).setStartDelay(0L)
                .setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR).start()
        }
    }

    private fun selectSearchFab(animation: Boolean) {
        val fabLayout = callback.getFabLayout() ?: return
        val searchFab = callback.getSearchFab() ?: return

        mShowActionFab = false

        if (animation) {
            val fab: View = fabLayout.primaryFab
            val delay: Long
            if (View.INVISIBLE == fab.visibility) {
                delay = 0L
            } else {
                delay = ANIMATE_TIME
                fab.animate().scaleX(0.0f).scaleY(0.0f).setListener(mActionFabAnimatorListener)
                    .setDuration(ANIMATE_TIME).setStartDelay(0L)
                    .setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR).start()
            }
            searchFab.visibility = View.VISIBLE
            searchFab.rotation = -45.0f
            searchFab.animate().scaleX(1.0f).scaleY(1.0f).rotation(0.0f).setListener(null)
                .setDuration(ANIMATE_TIME).setStartDelay(delay)
                .setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR).start()
        } else {
            fabLayout.setExpanded(false, false)
            val fab: View = fabLayout.primaryFab
            fab.visibility = View.INVISIBLE
            fab.scaleX = 0.0f
            fab.scaleY = 0.0f
            searchFab.visibility = View.VISIBLE
            searchFab.scaleX = 1.0f
            searchFab.scaleY = 1.0f
        }
    }

    private fun selectActionFab(animation: Boolean) {
        val fabLayout = callback.getFabLayout() ?: return
        val searchFab = callback.getSearchFab() ?: return

        mShowActionFab = true

        if (animation) {
            val delay: Long
            if (View.INVISIBLE == searchFab.visibility) {
                delay = 0L
            } else {
                delay = ANIMATE_TIME
                searchFab.animate().scaleX(0.0f).scaleY(0.0f).setListener(mSearchFabAnimatorListener)
                    .setDuration(ANIMATE_TIME).setStartDelay(0L)
                    .setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR).start()
            }
            val fab: View = fabLayout.primaryFab
            fab.visibility = View.VISIBLE
            fab.rotation = -45.0f
            fab.animate().scaleX(1.0f).scaleY(1.0f).rotation(0.0f).setListener(null)
                .setDuration(ANIMATE_TIME).setStartDelay(delay)
                .setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR).start()
        } else {
            fabLayout.setExpanded(false, false)
            val fab: View = fabLayout.primaryFab
            fab.visibility = View.VISIBLE
            fab.scaleX = 1.0f
            fab.scaleY = 1.0f
            searchFab.visibility = View.INVISIBLE
            searchFab.scaleX = 0.0f
            searchFab.scaleY = 0.0f
        }
    }

    /**
     * Handle SearchBar state change — update drawables and drawer lock mode.
     */
    fun onSearchBarStateChange(newState: Int, oldState: Int, animation: Boolean) {
        val leftDrawable = callback.getLeftDrawable() ?: return
        val rightDrawable = callback.getRightDrawable() ?: return

        when (oldState) {
            SearchBar.STATE_NORMAL -> {
                leftDrawable.setArrow(if (animation) ANIMATE_TIME else 0)
                rightDrawable.setDelete(if (animation) ANIMATE_TIME else 0)
            }
            SearchBar.STATE_SEARCH -> {
                if (newState == SearchBar.STATE_NORMAL) {
                    leftDrawable.setMenu(if (animation) ANIMATE_TIME else 0)
                    rightDrawable.setAdd(if (animation) ANIMATE_TIME else 0)
                }
            }
            SearchBar.STATE_SEARCH_LIST -> {
                if (newState == STATE_NORMAL) {
                    leftDrawable.setMenu(if (animation) ANIMATE_TIME else 0)
                    rightDrawable.setAdd(if (animation) ANIMATE_TIME else 0)
                }
            }
        }

        if (newState == STATE_NORMAL || newState == STATE_SIMPLE_SEARCH) {
            callback.setDrawerLockMode(LOCK_MODE_UNLOCKED, Gravity.LEFT)
            callback.setDrawerLockMode(LOCK_MODE_UNLOCKED, Gravity.RIGHT)
        } else {
            callback.setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
            callback.setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
        }
    }

    /**
     * Handle FabLayout expand/collapse — update drawable and drawer lock mode.
     */
    fun onFabExpand(expanded: Boolean) {
        val actionFabDrawable = callback.getActionFabDrawable() ?: return

        if (expanded) {
            callback.setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT)
            callback.setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
            actionFabDrawable.setDelete(ANIMATE_TIME)
        } else {
            callback.setDrawerLockMode(LOCK_MODE_UNLOCKED, Gravity.LEFT)
            callback.setDrawerLockMode(LOCK_MODE_UNLOCKED, Gravity.RIGHT)
            actionFabDrawable.setAdd(ANIMATE_TIME)
        }
    }

    companion object {
        const val STATE_NORMAL = 0
        const val STATE_SIMPLE_SEARCH = 1
        const val STATE_SEARCH = 2
        const val STATE_SEARCH_SHOW_LIST = 3

        private const val ANIMATE_TIME = 300L

        // DrawerLayout lock mode constants (avoid import dependency)
        private const val LOCK_MODE_UNLOCKED = 0
        private const val LOCK_MODE_LOCKED_CLOSED = 1
    }
}
