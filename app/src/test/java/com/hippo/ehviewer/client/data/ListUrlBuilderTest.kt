package com.hippo.ehviewer.client.data

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the Parcelable wire format and copy semantics of [ListUrlBuilder]
 * while the EhViewer-era image-search fields are removed: every field that
 * is written must be read back in the same order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ListUrlBuilderTest {

    private fun populated(): ListUrlBuilder = ListUrlBuilder().apply {
        mode = ListUrlBuilder.MODE_TAG
        pageIndex = 7
        category = 3
        keyword = "artist:someone"
        advanceSearch = 5
        minRating = 4
        pageFrom = 10
        pageTo = 20
    }

    @Test
    fun `parcel round-trip preserves every field`() {
        val original = populated()

        val parcel = Parcel.obtain()
        val restored = try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ListUrlBuilder.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }

        assertEquals(original.mode, restored.mode)
        assertEquals(original.pageIndex, restored.pageIndex)
        assertEquals(original.category, restored.category)
        assertEquals(original.keyword, restored.keyword)
        assertEquals(original.advanceSearch, restored.advanceSearch)
        assertEquals(original.minRating, restored.minRating)
        assertEquals(original.pageFrom, restored.pageFrom)
        assertEquals(original.pageTo, restored.pageTo)
    }

    @Test
    fun `set copies every field from the template`() {
        val template = populated()
        val target = ListUrlBuilder()

        target.set(template)

        assertEquals(template.mode, target.mode)
        assertEquals(template.pageIndex, target.pageIndex)
        assertEquals(template.category, target.category)
        assertEquals(template.keyword, target.keyword)
        assertEquals(template.advanceSearch, target.advanceSearch)
        assertEquals(template.minRating, target.minRating)
        assertEquals(template.pageFrom, target.pageFrom)
        assertEquals(template.pageTo, target.pageTo)
    }

    @Test
    fun `reset returns to the homepage state`() {
        val builder = populated()

        builder.reset()

        assertEquals(ListUrlBuilder.MODE_NORMAL, builder.mode)
        assertEquals(0, builder.pageIndex)
        assertEquals(null, builder.keyword)
        assertEquals(-1, builder.advanceSearch)
    }
}
