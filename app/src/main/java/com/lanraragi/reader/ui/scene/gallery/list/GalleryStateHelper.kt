package com.lanraragi.reader.ui.scene.gallery.list

import android.animation.Animator
import android.view.Gravity
import android.view.View
import com.lanraragi.framework.drawable.AddDeleteDrawable
import com.lanraragi.framework.drawable.DrawerArrowDrawable
import com.lanraragi.reader.R
import com.lanraragi.reader.widget.SearchBar
import com.lanraragi.framework.widget.FabLayout
import com.lanraragi.framework.widget.SearchBarMover
import com.lanraragi.reader.widget.ViewTransition
import com.lanraragi.reader.widget.SearchLayout
import com.lanraragi.framework.util.AnimationUtils
import android.animation.AnimatorListenerAdapter

/**
 * Renders the search chrome ([SearchChrome], owned by GalleryListViewModel)
 * for GalleryListScene: the advanced layout, the search bar, its icons and
 * drawer locks, and the FAB show/hide/select animations.
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

        /**
         * The search bar's right icon now belongs to the normal state ([normal])
         * or to a search state (clear text). Tank mode swaps its sort icon in.
         */
        fun onRightIconNormal(normal: Boolean) {}
    }

    /**
     * The chrome on screen. Only [render] changes it; the truth is
     * [GalleryListViewModel.searchChrome].
     */
    var rendered = SearchChrome.Normal
        private set
    private var mShowActionFab = true

    private val mActionFabAnimatorListener: Animator.AnimatorListener = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            callback.getFabLayout()?.let { (it.primaryFab as View).visibility = View.INVISIBLE }
        }
    }

    private val mSearchFabAnimatorListener: Animator.AnimatorListener = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            callback.getSearchFab()?.visibility = View.INVISIBLE
        }
    }

    /**
     * Shows [chrome]: the advanced layout, the search bar, its position and
     * the FABs follow what differs from the chrome on screen. [animation]
     * false for a restore.
     */
    fun render(chrome: SearchChrome, animation: Boolean) {
        val searchBar = callback.getSearchBar() ?: return
        val searchBarMover = callback.getSearchBarMover() ?: return
        val viewTransition = callback.getViewTransition() ?: return
        val searchLayout = callback.getSearchLayout() ?: return

        val old = rendered
        if (old == chrome) return
        rendered = chrome

        if (chrome.showsSearchLayout != old.showsSearchLayout) {
            viewTransition.showView(if (chrome.showsSearchLayout) 1 else 0, animation)
            if (chrome.showsSearchLayout) searchLayout.scrollSearchContainerToTop()
        }
        searchBar.setState(searchBarStateOf(chrome), animation)
        searchBarMover.returnSearchBarPosition()
        if ((old == SearchChrome.Normal) != (chrome == SearchChrome.Normal)) {
            if (chrome == SearchChrome.Normal) selectActionFab(animation) else selectSearchFab(animation)
        }
    }

    fun showActionFab() {
        val fabLayout = callback.getFabLayout() ?: return
        if (rendered == SearchChrome.Normal && !mShowActionFab) {
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
        if (rendered == SearchChrome.Normal && mShowActionFab) {
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
                callback.getSearchBar()?.setLeftIconContentDescription(R.string.cd_back)
                callback.getSearchBar()?.setRightIconContentDescription(R.string.cd_clear_search)
                callback.onRightIconNormal(false)
            }
            SearchBar.STATE_SEARCH -> {
                if (newState == SearchBar.STATE_NORMAL) {
                    leftDrawable.setMenu(if (animation) ANIMATE_TIME else 0)
                    rightDrawable.setAdd(if (animation) ANIMATE_TIME else 0)
                    callback.getSearchBar()?.setLeftIconContentDescription(R.string.cd_menu)
                    callback.getSearchBar()?.setRightIconContentDescription(R.string.search)
                    callback.onRightIconNormal(true)
                }
            }
            SearchBar.STATE_SEARCH_LIST -> {
                if (newState == SearchBar.STATE_NORMAL) {
                    leftDrawable.setMenu(if (animation) ANIMATE_TIME else 0)
                    rightDrawable.setAdd(if (animation) ANIMATE_TIME else 0)
                    callback.getSearchBar()?.setLeftIconContentDescription(R.string.cd_menu)
                    callback.getSearchBar()?.setRightIconContentDescription(R.string.search)
                    callback.onRightIconNormal(true)
                }
            }
        }

        // The drawers stay closed while the suggestion list is up (SearchChrome.showsSuggestions).
        if (newState != SearchBar.STATE_SEARCH_LIST) {
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
        /** The search bar's own state for [chrome]. */
        fun searchBarStateOf(chrome: SearchChrome): Int = when (chrome) {
            SearchChrome.Normal -> SearchBar.STATE_NORMAL
            SearchChrome.Advanced -> SearchBar.STATE_SEARCH
            SearchChrome.Simple, SearchChrome.AdvancedList -> SearchBar.STATE_SEARCH_LIST
        }

        private const val ANIMATE_TIME = 300L

        // DrawerLayout lock mode constants (avoid import dependency)
        private const val LOCK_MODE_UNLOCKED = 0
        private const val LOCK_MODE_LOCKED_CLOSED = 1
    }
}
