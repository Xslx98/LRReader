package com.lanraragi.reader.ui.scene

import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.client.api.data.LRRCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks how a category tap turns into a gallery-list request (spec
 * 2026-09-15, Q1/Q2): both static and dynamic categories open by id through
 * [ListUrlBuilder.categoryId]; the keyword stays empty so the search bar
 * never shows an opaque `category:SET_…` string, and a dynamic category's
 * raw query string is never used as the keyword (the server resolves it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CategoryListRequestTest {

    private fun category(id: String?, name: String?, search: String? = null) =
        LRRCategory().also { it.id = id; it.name = name; it.search = search }

    @Test
    fun `static category maps to id and name with an empty keyword`() {
        val builder = categoryListBuilder(category("SET_1704939135", "Favourites"))

        assertNotNull(builder)
        assertEquals(ListUrlBuilder.MODE_NORMAL, builder!!.mode)
        assertEquals("SET_1704939135", builder.categoryId)
        assertEquals("Favourites", builder.categoryName)
        assertNull(builder.keyword)
    }

    @Test
    fun `dynamic category also maps to id - its search string is not the keyword`() {
        val builder = categoryListBuilder(category("SET_2", "Recent", search = "artist:foo -tag:bar"))

        assertNotNull(builder)
        assertEquals("SET_2", builder!!.categoryId)
        assertEquals("Recent", builder.categoryName)
        assertNull(builder.keyword)
    }

    @Test
    fun `category without an id yields null`() {
        assertNull(categoryListBuilder(category(null, "Nameless id")))
        assertNull(categoryListBuilder(category("  ", "Blank id")))
    }
}
