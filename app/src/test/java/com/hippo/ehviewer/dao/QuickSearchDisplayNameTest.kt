package com.hippo.ehviewer.dao

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A quick search whose name was wiped by the v1.21.0 drawer bug must still
 * render something meaningful: fall back to the category name, then the
 * category id, then the keyword. [QuickSearch.toString] (used by the
 * drawer's ArrayAdapter) and the management screen both go through
 * [QuickSearch.displayName].
 */
class QuickSearchDisplayNameTest {

    @Test
    fun `name wins when present`() {
        assertEquals("Mine", QuickSearch(name = "Mine", keyword = "k", categoryName = "C").displayName)
    }

    @Test
    fun `blank name falls back to category name, id, then keyword`() {
        assertEquals("Favourites", QuickSearch(name = "", categoryId = "SET_1", categoryName = "Favourites").displayName)
        assertEquals("SET_1", QuickSearch(name = null, categoryId = "SET_1").displayName)
        assertEquals("touhou", QuickSearch(name = null, keyword = "touhou").displayName)
        assertEquals("", QuickSearch(name = null).displayName)
    }

    @Test
    fun `toString is the display name`() {
        assertEquals("Favourites", QuickSearch(name = null, categoryId = "SET_1", categoryName = "Favourites").toString())
    }
}
