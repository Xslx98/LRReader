package com.hippo.ehviewer.ui.scene.gallery.list

import android.view.Gravity
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.ehviewer.widget.SearchLayout

/**
 * Handles SearchBar interaction callbacks and search query construction
 * for [GalleryListScene].
 *
 * Owns: onApplySearch logic, SearchBar click routing, SearchBarMover
 * helper queries, back-press search state handling.
 */
internal class GalleryListSearchHelper(private val callback: Callback) {

    interface Callback {
        fun getSearchBar(): SearchBar?
        fun getSearchLayout(): SearchLayout?
        fun getUrlBuilder(): ListUrlBuilder?
        fun getContentHelper(): GalleryListDataHelper?
        fun getStateHelper(): GalleryStateHelper?
        fun getRecyclerView(): RecyclerView?
        fun onUpdateUrlBuilder()
        fun showTip(message: String, length: Int)
        fun toggleDrawer(gravity: Int)
        fun hideSoftInput()
    }

    // SearchBar.Helper callbacks

    fun onClickTitle() {
        val currentState = callback.getStateHelper()?.state ?: GalleryStateHelper.STATE_NORMAL
        if (currentState == GalleryStateHelper.STATE_NORMAL) {
            callback.getStateHelper()?.setState(GalleryStateHelper.STATE_SIMPLE_SEARCH)
        }
    }

    fun onClickLeftIcon() {
        val searchBar = callback.getSearchBar() ?: return
        if (searchBar.getState() == SearchBar.STATE_NORMAL) {
            callback.toggleDrawer(Gravity.LEFT)
        } else {
            callback.getStateHelper()?.setState(GalleryStateHelper.STATE_NORMAL)
        }
    }

    fun onClickRightIcon() {
        val searchBar = callback.getSearchBar() ?: return
        if (searchBar.getState() == SearchBar.STATE_NORMAL) {
            callback.getStateHelper()?.setState(GalleryStateHelper.STATE_SEARCH)
        } else {
            searchBar.setText("")
        }
    }

    fun onSearchEditTextClick() {
        val currentState = callback.getStateHelper()?.state ?: GalleryStateHelper.STATE_NORMAL
        if (currentState == GalleryStateHelper.STATE_SEARCH) {
            callback.getStateHelper()?.setState(GalleryStateHelper.STATE_SEARCH_SHOW_LIST)
        }
    }

    fun onApplySearch(query: String) {
        val urlBuilder = callback.getUrlBuilder() ?: return
        val helper = callback.getContentHelper() ?: return
        val searchLayout = callback.getSearchLayout() ?: return

        val cleanQuery = query.replace("\r", "").replace("\n", "")
        val currentState = callback.getStateHelper()?.state ?: GalleryStateHelper.STATE_NORMAL

        if (currentState == GalleryStateHelper.STATE_SEARCH ||
            currentState == GalleryStateHelper.STATE_SEARCH_SHOW_LIST
        ) {
            try {
                searchLayout.formatListUrlBuilder(urlBuilder, cleanQuery)
            } catch (e: EhException) {
                callback.showTip(e.message ?: "", BaseScene.LENGTH_LONG)
                return
            }
        } else {
            val oldMode = urlBuilder.mode
            val newMode = if (oldMode == ListUrlBuilder.MODE_SUBSCRIPTION) {
                ListUrlBuilder.MODE_SUBSCRIPTION
            } else {
                ListUrlBuilder.MODE_NORMAL
            }
            urlBuilder.reset()
            urlBuilder.mode = newMode
            urlBuilder.keyword = cleanQuery
        }
        callback.onUpdateUrlBuilder()
        helper.refresh()
        callback.getStateHelper()?.setState(GalleryStateHelper.STATE_NORMAL)
    }

    fun onSearchFabClick() {
        val currentState = callback.getStateHelper()?.state ?: GalleryStateHelper.STATE_NORMAL
        val searchBar = callback.getSearchBar() ?: return
        if (GalleryStateHelper.STATE_NORMAL != currentState) {
            searchBar.applySearch(false)
            callback.hideSoftInput()
        }
    }

    // SearchBarMover.Helper queries

    fun isValidView(recyclerView: RecyclerView): Boolean {
        val currentState = callback.getStateHelper()?.state ?: GalleryStateHelper.STATE_NORMAL
        return (currentState == GalleryStateHelper.STATE_NORMAL && recyclerView === callback.getRecyclerView()) ||
                (currentState == GalleryStateHelper.STATE_SEARCH && recyclerView === callback.getSearchLayout())
    }

    fun getValidRecyclerView(): RecyclerView? {
        val currentState = callback.getStateHelper()?.state ?: GalleryStateHelper.STATE_NORMAL
        return if (currentState == GalleryStateHelper.STATE_NORMAL ||
            currentState == GalleryStateHelper.STATE_SIMPLE_SEARCH
        ) {
            callback.getRecyclerView()
        } else {
            callback.getSearchLayout()
        }
    }

    fun forceShowSearchBar(): Boolean {
        val currentState = callback.getStateHelper()?.state ?: GalleryStateHelper.STATE_NORMAL
        return currentState == GalleryStateHelper.STATE_SIMPLE_SEARCH ||
                currentState == GalleryStateHelper.STATE_SEARCH_SHOW_LIST
    }

    /**
     * Handle back press for search states.
     * @return true if the back press was consumed by search state logic
     */
    fun handleSearchBackPress(): Boolean {
        val currentState = callback.getStateHelper()?.state ?: GalleryStateHelper.STATE_NORMAL
        return when (currentState) {
            GalleryStateHelper.STATE_SIMPLE_SEARCH, GalleryStateHelper.STATE_SEARCH -> {
                callback.getStateHelper()?.setState(GalleryStateHelper.STATE_NORMAL)
                true
            }
            GalleryStateHelper.STATE_SEARCH_SHOW_LIST -> {
                callback.getStateHelper()?.setState(GalleryStateHelper.STATE_SEARCH)
                true
            }
            else -> false
        }
    }
}

