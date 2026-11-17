package com.lanraragi.reader.ui.scene.gallery.list

import androidx.lifecycle.SavedStateHandle
import com.lanraragi.reader.widget.SearchBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gallery list's search chrome: one value, its back steps and what it renders. */
class SearchChromeTest {

    @Test
    fun `back leaves search one step at a time`() {
        assertEquals(SearchChrome.Normal, SearchChrome.Simple.back())
        assertEquals(SearchChrome.Normal, SearchChrome.Advanced.back())
        assertEquals("the suggestions close first", SearchChrome.Advanced, SearchChrome.AdvancedList.back())
        assertNull("nothing to undo over the list", SearchChrome.Normal.back())
    }

    /**
     * The drawers lock while the suggestions are up. The old check compared
     * the search bar's states to the list's constants and only worked because
     * both NORMALs were 0.
     */
    @Test
    fun `the suggestion list is up in the simple search and the advanced list only`() {
        assertTrue(SearchChrome.Simple.showsSuggestions)
        assertTrue(SearchChrome.AdvancedList.showsSuggestions)
        assertFalse(SearchChrome.Advanced.showsSuggestions)
        assertFalse(SearchChrome.Normal.showsSuggestions)

        for (chrome in SearchChrome.entries) {
            assertEquals(
                chrome.name,
                chrome.showsSuggestions,
                GalleryStateHelper.searchBarStateOf(chrome) == SearchBar.STATE_SEARCH_LIST,
            )
        }
    }

    @Test
    fun `the advanced layout covers the list in the advanced states`() {
        assertEquals(
            listOf(SearchChrome.Advanced, SearchChrome.AdvancedList),
            SearchChrome.entries.filter { it.showsSearchLayout },
        )
        assertEquals(SearchBar.STATE_SEARCH, GalleryStateHelper.searchBarStateOf(SearchChrome.Advanced))
        assertEquals(SearchBar.STATE_NORMAL, GalleryStateHelper.searchBarStateOf(SearchChrome.Normal))
    }

    /** Saved with the screen: a process-death restore comes back to the same chrome. */
    @Test
    fun `the chrome is kept in the saved state`() {
        val saved = SavedStateHandle()
        GalleryListViewModel(saved).setSearchChrome(SearchChrome.AdvancedList)

        assertEquals(SearchChrome.AdvancedList, GalleryListViewModel(saved).searchChrome.value)
        assertEquals(SearchChrome.Normal, GalleryListViewModel().searchChrome.value)
    }
}
