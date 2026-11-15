package com.lanraragi.reader.widget

import android.content.Context
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import kotlinx.parcelize.Parcelize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ContentLayout.ContentHelper] keeps its rows across a view-state save and
 * restore in the host's [SavedRows] (rule D11 retired the
 * process-wide GlobalStuff map that did this): a restore with the slot shows
 * the same rows without a reload; without one, or once the slot was taken, it
 * loads the list again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ContentHelperSavedRowsTest {

    @Parcelize
    data class Row(val id: String) : Parcelable

    private class Helper(
        private val ctx: Context,
        private val slot: SavedRows<Row>?,
    ) : ContentLayout.ContentHelper<Row>() {
        var requests = 0
        var lastTaskId = -1

        override val savedRows get() = slot

        override fun getPageData(taskId: Int, type: Int, page: Int) {
            requests++
            lastTaskId = taskId
        }
        override fun getPageData(taskId: Int, type: Int, page: Int, append: String?) =
            getPageData(taskId, type, page)
        override fun getExPageData(pageAction: Int, taskId: Int, page: Int) = Unit
        override fun getContext(): Context = ctx
        override fun notifyDataSetChanged() = Unit
        override fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int) = Unit
        override fun notifyItemRangeInserted(positionStart: Int, itemCount: Int) = Unit
        override fun notifyItemRangeChanged(positionStart: Int, itemCount: Int) = Unit
        override fun notifyItemMoved(fromPosition: Int, toPosition: Int) = Unit
        override fun isDuplicate(d1: Row, d2: Row): Boolean = d1.id == d2.id
    }

    private val ctx: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(com.lanraragi.reader.R.style.AppTheme)
    }

    /** A helper attached to its own layout, as a rebuilt scene view has. */
    private fun attached(slot: SavedRows<Row>?) =
        Helper(ctx, slot).also { ContentLayout(ctx).setHelper(it) }

    /** A helper that loaded one page of [ids], then saved its view state. */
    private fun savedAfterLoading(slot: SavedRows<Row>?, vararg ids: String): Parcelable {
        val helper = attached(slot)
        helper.refresh()
        helper.onGetPageData(helper.lastTaskId, 1, 1, ids.map { Row(it) })
        return helper.saveInstanceState(null)
    }

    @Test
    fun restoreWithTheSlot_showsTheSameRowsWithoutAReload() {
        val slot = SavedRows<Row>()
        val state = savedAfterLoading(slot, "a", "b", "c")

        val restored = attached(slot)
        restored.restoreInstanceState(state)

        assertEquals(listOf("a", "b", "c"), restored.data.map { it.id })
        assertEquals(0, restored.requests)
    }

    @Test
    fun restoreWithoutASlot_loadsTheListAgain() {
        val state = savedAfterLoading(null, "a", "b")

        val restored = attached(null)
        restored.restoreInstanceState(state)

        assertTrue(restored.data.isEmpty())
        assertEquals(1, restored.requests)
    }

    @Test
    fun theSlotHandsTheRowsOverOnce() {
        val slot = SavedRows<Row>()
        val state = savedAfterLoading(slot, "a")
        attached(slot).restoreInstanceState(state)

        val second = attached(slot)
        second.restoreInstanceState(state)

        assertTrue(second.data.isEmpty())
        assertEquals(1, second.requests)
    }
}
