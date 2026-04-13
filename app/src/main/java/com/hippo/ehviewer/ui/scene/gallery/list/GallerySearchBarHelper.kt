package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.content.Intent
import android.view.Gravity
import androidx.recyclerview.widget.RecyclerView
import com.hippo.drawerlayout.DrawerLayout
import com.hippo.easyrecyclerview.FastScroller
import com.hippo.ehviewer.R
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.ehviewer.widget.SearchLayout
import com.hippo.widget.SearchBarMover

/**
 * Consolidates all SearchBar-related interface implementations that were formerly
 * on [GalleryListScene]: [SearchBar.Helper], [SearchBar.OnStateChangeListener],
 * [FastScroller.OnDragHandlerListener], [SearchLayout.Helper], and
 * [SearchBarMover.Helper].
 *
 * The Scene registers this helper instead of implementing the interfaces directly,
 * reducing its interface count and line count.
 *
 * Uses property-provider lambdas instead of a Callback interface so the Scene
 * initialization is concise (single-expression per dependency).
 */
internal class GallerySearchBarHelper(
    private val listSearchHelper: () -> GalleryListSearchHelper?,
    private val stateHelper: () -> GalleryStateHelper?,
    private val searchBarMover: () -> SearchBarMover?,
    private val contentHelper: () -> GalleryListDataHelper?,
    private val setDrawerLockMode: (Int, Int) -> Unit,
    private val doBackPress: () -> Unit,
    private val doStartActivityForResult: (Intent, Int) -> Unit,
    private val doGetString: (Int) -> String
) : SearchBar.Helper, SearchBar.OnStateChangeListener,
    FastScroller.OnDragHandlerListener, SearchLayout.Helper,
    SearchBarMover.Helper {

    // -- SearchBar.Helper --

    override fun onClickTitle() {
        listSearchHelper()?.onClickTitle()
    }

    @SuppressLint("RtlHardcoded")
    override fun onClickLeftIcon() {
        listSearchHelper()?.onClickLeftIcon()
    }

    override fun onClickRightIcon() {
        listSearchHelper()?.onClickRightIcon()
    }

    override fun onSearchEditTextClick() {
        listSearchHelper()?.onSearchEditTextClick()
    }

    override fun onApplySearch(query: String) {
        listSearchHelper()?.onApplySearch(query)
    }

    override fun onSearchEditTextBackPressed() {
        doBackPress()
    }

    // -- SearchBar.OnStateChangeListener --

    @SuppressLint("RtlHardcoded")
    override fun onStateChange(searchBar: SearchBar, newState: Int, oldState: Int, animation: Boolean) {
        stateHelper()?.onSearchBarStateChange(newState, oldState, animation)
    }

    // -- FastScroller.OnDragHandlerListener --

    @SuppressLint("RtlHardcoded")
    override fun onStartDragHandler() {
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT)
    }

    @SuppressLint("RtlHardcoded")
    override fun onEndDragHandler() {
        setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT)
        searchBarMover()?.returnSearchBarPosition()
    }

    // -- SearchLayout.Helper --

    override fun onChangeSearchMode() {
        searchBarMover()?.showSearchBar() ?: Unit
    }

    override fun onSelectImage() {
        val intent = Intent()
        intent.type = "image/*"
        intent.action = Intent.ACTION_GET_CONTENT
        doStartActivityForResult(
            Intent.createChooser(intent, doGetString(R.string.select_image)),
            GalleryListScene.REQUEST_CODE_SELECT_IMAGE
        )
    }

    override fun onSortChanged() {
        contentHelper()?.refresh()
    }

    // -- SearchBarMover.Helper --

    override fun isValidView(recyclerView: RecyclerView): Boolean =
        listSearchHelper()?.isValidView(recyclerView) ?: false

    override fun getValidRecyclerView(): RecyclerView? =
        listSearchHelper()?.getValidRecyclerView()

    override fun forceShowSearchBar(): Boolean =
        listSearchHelper()?.forceShowSearchBar() ?: false
}
