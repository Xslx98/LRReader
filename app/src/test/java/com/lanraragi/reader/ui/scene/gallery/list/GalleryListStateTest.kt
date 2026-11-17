package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.R
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListState.Companion.edited
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListState.Companion.failed
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListState.Companion.pageLoaded
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListState.Companion.start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gallery list's paging state: every change is one pure step. */
class GalleryListStateTest {

    private fun row(id: Int) = Archive(
        arcid = "%040d".format(id), title = "t$id", tags = emptyMap(), pagecount = 1, progress = 0,
        extension = "zip", filename = "f$id.zip", thumbnailUrl = "", rating = 0f, isnew = false,
        lastreadtime = 0L, summary = null, serverProfileId = 0L,
    )

    private fun rows(range: IntRange) = range.map(::row)

    private val empty = R.string.gallery_list_empty_hit

    /** A list showing [pages] pages of 10 rows each, out of [pageCount]. */
    private fun shown(pages: Int, pageCount: Int): GalleryListState {
        var state: GalleryListState = start(GalleryListState.Idle, GalleryLoadKind.Initial, 1)
        state = pageLoaded(state, 1, rows(0 until 10), pageCount, empty)
        for (p in 1 until pages) {
            state = start(state, GalleryLoadKind.NextPage, p + 1)
            state = pageLoaded(state, p + 1, rows(p * 10 until p * 10 + 10), pageCount, empty)
        }
        return state
    }

    @Test
    fun `a first load shows its page, an empty answer is empty with its wording`() {
        val loading = start(GalleryListState.Idle, GalleryLoadKind.Initial, 1)
        val content = pageLoaded(loading, 1, rows(0 until 10), pageCount = 3, emptyText = empty)
        assertTrue(content is GalleryListState.Content)
        assertEquals(listOf(10), content.pages.pageEnds)
        assertTrue(content.pages.canLoadMore)

        val tanks = pageLoaded(loading, 1, emptyList(), pageCount = 1, emptyText = R.string.tankoubons_empty)
        assertTrue(tanks is GalleryListState.Empty)
        assertEquals(R.string.tankoubons_empty, tanks.pages.emptyText)
    }

    @Test
    fun `a superseded load's answer and failure change nothing`() {
        val first = start(GalleryListState.Idle, GalleryLoadKind.Initial, 1)
        val second = start(first, GalleryLoadKind.Refresh, 2)

        assertSame(second, pageLoaded(second, 1, rows(0 until 10), 1, empty))
        assertSame(second, failed(second, 1, "late"))
    }

    @Test
    fun `next pages append, dropping rows the recent ones already show`() {
        var state = shown(pages = 1, pageCount = 3)
        state = start(state, GalleryLoadKind.NextPage, 9)
        // The server shifted: two rows of page 0 come again on page 1.
        state = pageLoaded(state, 9, rows(8 until 18), pageCount = 3, emptyText = empty)

        assertEquals(rows(0 until 18), state.pages.rows)
        assertEquals(listOf(10, 18), state.pages.pageEnds)
    }

    @Test
    fun `pulling up at the end loads the last page again in place`() {
        var state = shown(pages = 2, pageCount = 2)
        assertFalse(state.pages.canLoadMore)
        state = start(state, GalleryLoadKind.LastPage, 9)
        // Two new archives on the last page.
        state = pageLoaded(state, 9, rows(10 until 22), pageCount = 2, emptyText = empty)

        assertEquals(rows(0 until 22), state.pages.rows)
        assertEquals(listOf(10, 22), state.pages.pageEnds)
    }

    @Test
    fun `a load that hid the rows fails to the error, one that kept them goes back to them`() {
        val list = shown(pages = 1, pageCount = 1)

        val refresh = failed(start(list, GalleryLoadKind.Refresh, 7), 7, "timeout")
        assertEquals(GalleryListState.Error("timeout", list.pages), refresh)

        for (kind in listOf(GalleryLoadKind.Pull, GalleryLoadKind.NextPage, GalleryLoadKind.LastPage)) {
            assertEquals(kind.name, GalleryListState.Content(list.pages), failed(start(list, kind, 7), 7, "timeout"))
        }
        val first = failed(start(GalleryListState.Idle, GalleryLoadKind.Pull, 7), 7, "timeout")
        assertTrue("nothing to go back to", first is GalleryListState.Error)
    }

    @Test
    fun `edits move the page ends with the rows, and a list edited to nothing is empty`() {
        var state = shown(pages = 2, pageCount = 2)

        state = edited(state) { it.removeAt(3) }
        assertEquals(listOf(9, 19), state.pages.pageEnds)
        state = edited(state) { it.insertAt(12, row(99)) }
        assertEquals(listOf(9, 20), state.pages.pageEnds)
        assertEquals(row(99), state.pages.rows[12])
        state = edited(state) { it.replaceAt(0, row(98)) }
        assertEquals(row(98), state.pages.rows[0])

        var single = shown(pages = 1, pageCount = 1)
        repeat(10) { single = edited(single) { it.removeAt(0) } }
        assertTrue(single is GalleryListState.Empty)
    }

    @Test
    fun `an edit during a load keeps it running`() {
        val loading = start(shown(pages = 1, pageCount = 2), GalleryLoadKind.NextPage, 5)
        val edited = edited(loading) { it.removeAt(0) }

        assertTrue(edited is GalleryListState.Loading)
        assertEquals(5, edited.awaitedTask)
        assertEquals(9, edited.pages.rows.size)
    }

    @Test
    fun `the slot the scene shows for each state`() {
        val list = shown(pages = 1, pageCount = 1)
        assertTrue(GalleryListDataHelper.screenOf(GalleryListState.Idle) is com.lanraragi.reader.ui.list.ListScreenState.Loading)
        assertTrue(
            "a refresh hides the rows behind the skeleton",
            GalleryListDataHelper.screenOf(start(list, GalleryLoadKind.Refresh, 2)) is com.lanraragi.reader.ui.list.ListScreenState.Loading,
        )
        assertEquals(
            "a pull keeps them",
            list.pages.rows,
            GalleryListDataHelper.screenOf(start(list, GalleryLoadKind.Pull, 2)).items,
        )
        assertTrue(
            "an error shows its message, not the stale rows",
            GalleryListDataHelper.screenOf(GalleryListState.Error("x", list.pages)).items.isEmpty(),
        )
    }
}
