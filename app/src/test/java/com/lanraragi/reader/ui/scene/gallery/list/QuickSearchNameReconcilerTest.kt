package com.lanraragi.reader.ui.scene.gallery.list

import com.lanraragi.reader.dao.QuickSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the v1.21.0 quick-search name wipe: the bookmarks drawer
 * used to rewrite every colon-free name to the entry's keyword whenever tag
 * translations were off and persist that — a category quick search has no
 * keyword, so its name became NULL on the next drawer load, and the
 * "Category · keyword" default names were clobbered too. The reconciler
 * now only performs the translation-on rewrite (tag-shaped names) and never
 * touches names when translations are off.
 */
class QuickSearchNameReconcilerTest {

    private fun qs(name: String?, keyword: String? = null, categoryId: String? = null) =
        QuickSearch(name = name, keyword = keyword, categoryId = categoryId)

    private val upper: (Array<String>) -> String = { parts -> parts.joinToString(":") { it.uppercase() } }

    @Test
    fun `translations off never rewrites names`() {
        val category = qs("Favourites", keyword = null, categoryId = "SET_1")
        val defaultName = qs("Favourites · bar", keyword = "bar", categoryId = "SET_1")
        val custom = qs("My search", keyword = "touhou")

        val changed = QuickSearchNameReconciler.reconcile(listOf(category, defaultName, custom), false, upper)

        assertTrue(changed.isEmpty())
        assertEquals("Favourites", category.name)
        assertEquals("Favourites · bar", defaultName.name)
        assertEquals("My search", custom.name)
    }

    @Test
    fun `translations on rewrites only tag-shaped names`() {
        val tag = qs("artist:someone", keyword = "artist:someone")
        val category = qs("Favourites", keyword = null, categoryId = "SET_1")
        val twoColons = qs("a:b:c", keyword = "a:b:c")

        val changed = QuickSearchNameReconciler.reconcile(listOf(tag, category, twoColons), true, upper)

        assertEquals(listOf(tag), changed)
        assertEquals("ARTIST:SOMEONE", tag.name)
        assertEquals("Favourites", category.name)
        assertEquals("a:b:c", twoColons.name)
    }

    @Test
    fun `null names are left alone`() {
        val broken = qs(null, keyword = null, categoryId = "SET_1")

        assertTrue(QuickSearchNameReconciler.reconcile(listOf(broken), true, upper).isEmpty())
        assertTrue(QuickSearchNameReconciler.reconcile(listOf(broken), false, upper).isEmpty())
    }
}
