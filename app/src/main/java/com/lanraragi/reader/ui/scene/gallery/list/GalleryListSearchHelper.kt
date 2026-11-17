package com.lanraragi.reader.ui.scene.gallery.list

import android.view.Gravity
import androidx.recyclerview.widget.RecyclerView
import com.lanraragi.reader.archive.ListUrlBuilder
import com.lanraragi.reader.client.exception.AppException
import com.lanraragi.reader.ui.scene.BaseScene
import com.lanraragi.reader.widget.SearchBar
import com.lanraragi.reader.widget.SearchLayout

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
        fun getRecyclerView(): RecyclerView?
        fun onUpdateUrlBuilder()
        fun showTip(message: String, length: Int)
        fun toggleDrawer(gravity: Int)
        fun hideSoftInput()

        /** Tank mode: the normal-state right icon opens the sort menu. @return handled */
        fun onTankSortClick(): Boolean = false

        /** The search chrome now (GalleryListViewModel.searchChrome). */
        fun chrome(): SearchChrome

        /** Moves the search chrome to [chrome]. */
        fun setChrome(chrome: SearchChrome)
    }

    // SearchBar.Helper callbacks

    fun onClickTitle() {
        val currentState = callback.chrome()
        if (currentState == SearchChrome.Normal) {
            callback.setChrome(SearchChrome.Simple)
        }
    }

    fun onClickLeftIcon() {
        val searchBar = callback.getSearchBar() ?: return
        if (searchBar.getState() == SearchBar.STATE_NORMAL) {
            callback.toggleDrawer(Gravity.LEFT)
        } else {
            callback.setChrome(SearchChrome.Normal)
        }
    }

    fun onClickRightIcon() {
        val searchBar = callback.getSearchBar() ?: return
        if (searchBar.getState() == SearchBar.STATE_NORMAL) {
            if (callback.onTankSortClick()) return
            callback.setChrome(SearchChrome.Advanced)
        } else {
            searchBar.setText("")
        }
    }

    fun onSearchEditTextClick() {
        val currentState = callback.chrome()
        if (currentState == SearchChrome.Advanced) {
            callback.setChrome(SearchChrome.AdvancedList)
        }
    }

    fun onApplySearch(query: String) {
        val urlBuilder = callback.getUrlBuilder() ?: return
        val helper = callback.getContentHelper() ?: return
        val searchLayout = callback.getSearchLayout() ?: return

        val cleanQuery = query.replace("\r", "").replace("\n", "")
        val currentState = callback.chrome()

        if (currentState == SearchChrome.Advanced ||
            currentState == SearchChrome.AdvancedList
        ) {
            try {
                searchLayout.formatListUrlBuilder(urlBuilder, cleanQuery)
            } catch (e: AppException) {
                callback.showTip(e.message ?: "", BaseScene.LENGTH_LONG)
                return
            }
        } else {
            applyQuery(urlBuilder, cleanQuery)
        }
        callback.onUpdateUrlBuilder()
        helper.refresh()
        callback.setChrome(SearchChrome.Normal)
    }

    fun onSearchFabClick() {
        val currentState = callback.chrome()
        val searchBar = callback.getSearchBar() ?: return
        if (SearchChrome.Normal != currentState) {
            searchBar.applySearch(false)
            callback.hideSoftInput()
        }
    }

    // SearchBarMover.Helper queries

    fun isValidView(recyclerView: RecyclerView): Boolean {
        val currentState = callback.chrome()
        return (currentState == SearchChrome.Normal && recyclerView === callback.getRecyclerView()) ||
                (currentState == SearchChrome.Advanced && recyclerView === callback.getSearchLayout())
    }

    fun getValidRecyclerView(): RecyclerView? {
        val currentState = callback.chrome()
        return if (currentState == SearchChrome.Normal ||
            currentState == SearchChrome.Simple
        ) {
            callback.getRecyclerView()
        } else {
            callback.getSearchLayout()
        }
    }

    fun forceShowSearchBar(): Boolean {
        val currentState = callback.chrome()
        return currentState == SearchChrome.Simple ||
                currentState == SearchChrome.AdvancedList
    }

    /**
     * Handle back press for search states.
     * @return true if the back press was consumed by search state logic
     */
    /** Whether [handleSearchBackPress] would consume a back now. */
    fun isSearchBackPending(): Boolean = callback.chrome().back() != null

    fun handleSearchBackPress(): Boolean {
        val back = callback.chrome().back() ?: return false
        callback.setChrome(back)
        return true
    }

    companion object {
        /**
         * Normal-state query application: a fresh search that stays inside the
         * current category (spec 2026-09-15) and keeps subscription mode as before.
         * Pure so the rule is testable without an attached list view.
         */
        @JvmStatic
        fun applyQuery(urlBuilder: ListUrlBuilder, cleanQuery: String) {
            val keepSubscription = urlBuilder.mode == ListUrlBuilder.MODE_SUBSCRIPTION
            urlBuilder.setKeywordKeepingCategory(cleanQuery)
            if (keepSubscription) {
                urlBuilder.mode = ListUrlBuilder.MODE_SUBSCRIPTION
            }
        }
    }
}

