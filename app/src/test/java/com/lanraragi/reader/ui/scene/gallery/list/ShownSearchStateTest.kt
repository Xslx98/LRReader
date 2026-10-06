package com.lanraragi.reader.ui.scene.gallery.list

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import com.lanraragi.reader.client.data.ListUrlBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 2026-10-06 C01: every list scene on the back stack shared the
 * activity-scoped [GalleryListViewModel]'s search params, so the last list to
 * load won. Home list A -> detail -> tag search list B -> back to A (no reload)
 * -> opening an archive from A published B's filter, and "continue to the next
 * archive" walked B's results. Each scene now keeps its own [ShownSearchState],
 * fed by its own [GalleryListDataHelper] (wired like GalleryListHelperFactory).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ShownSearchStateTest {

    /** The scene-side wiring of GalleryListHelperFactory, minus the views. */
    private class SceneCallback(val shown: ShownSearchState) : GalleryListDataHelper.Callback {
        override fun getHostContext(): Context? = null
        override fun getUrlBuilder(): ListUrlBuilder? = null
        override fun getSortBy(): String = "date_added"
        override fun getSortOrder(): String = "desc"
        override fun notifyAdapterDataSetChanged() {}
        override fun notifyAdapterItemRangeRemoved(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemRangeInserted(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemRangeChanged(positionStart: Int, itemCount: Int) {}
        override fun notifyAdapterItemMoved(fromPosition: Int, toPosition: Int) {}
        override fun showSearchBar() {}
        override fun showActionFab() {}
        override fun getString(resId: Int): String = ""
        override fun exitMultiSelect() {}
        override fun onSearchLoaded(params: GalleryListViewModel.SearchParams) {
            shown.onLoaded(params)
        }
    }

    private val homeQuery = GalleryListViewModel.SearchParams(
        filter = "artist:alice", sortby = "title", order = "asc",
    )
    private val tagQuery = GalleryListViewModel.SearchParams(
        filter = "parody:bob$", category = "SET_9",
    )

    @Test
    fun `a list pushed on top does not change the query of the list below`() {
        val shownA = ShownSearchState()
        val helperA = GalleryListDataHelper(SceneCallback(shownA))
        helperA.recordLoadedSearch(taskId = 0, issued = homeQuery)

        // Tag search from a detail page opens list B, which loads its own query.
        val shownB = ShownSearchState()
        val helperB = GalleryListDataHelper(SceneCallback(shownB))
        helperB.recordLoadedSearch(taskId = 0, issued = tagQuery)

        // Back to A without a reload: an archive opened from A continues A's query.
        assertEquals(homeQuery, shownA.forReadingContext())
        assertEquals(tagQuery, shownB.forReadingContext())
    }

    @Test
    fun `before any load the reading context carries the default query`() {
        val shown = ShownSearchState()

        assertNull(shown.params)
        assertEquals(GalleryListViewModel.SearchParams(), shown.forReadingContext())
    }

    @Test
    fun `the shown query survives scene recreation`() {
        val shown = ShownSearchState()
        shown.onLoaded(homeQuery)
        val out = Bundle()
        shown.save(out)

        // Through a Parcel, as the saved state is across process death.
        val parcel = Parcel.obtain()
        val restoredBundle = try {
            out.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel).apply {
                classLoader = GalleryListViewModel.SearchParams::class.java.classLoader
            }
        } finally {
            parcel.recycle()
        }
        val restored = ShownSearchState()
        restored.restore(restoredBundle)

        assertEquals(homeQuery, restored.forReadingContext())
    }
}
