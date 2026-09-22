package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.tankoubon.TankPageGridLayout.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [TankPageGridLayout]: divider positions, global↔position mapping, span sizes (spec 2026-09-22 §4.7). */
class TankPageGridLayoutTest {

    @Test
    fun build_placesOneDividerBeforeEachMemberAndNumbersPagesGlobally() {
        val layout = TankPageGridLayout.build(listOf(2, 3))

        assertEquals(
            listOf(
                Item.Divider(0), Item.Page(0, 0, 0), Item.Page(1, 0, 1),
                Item.Divider(1), Item.Page(2, 1, 0), Item.Page(3, 1, 1), Item.Page(4, 1, 2),
            ),
            layout.items,
        )
        assertEquals(5, layout.totalPages)
        assertEquals(7, layout.size)
    }

    @Test
    fun zeroPageMembersKeepTheirDivider() {
        val layout = TankPageGridLayout.build(listOf(1, 0, 1))

        assertEquals(
            listOf(Item.Divider(0), Item.Page(0, 0, 0), Item.Divider(1), Item.Divider(2), Item.Page(1, 2, 0)),
            layout.items,
        )
    }

    @Test
    fun positionOfGlobalAndGlobalAtAreInverses() {
        val layout = TankPageGridLayout.build(listOf(2, 3))

        for (global0 in 0 until layout.totalPages) {
            assertEquals(global0, layout.globalAt(layout.positionOfGlobal(global0)))
        }
        assertEquals(4, layout.positionOfGlobal(2))
        assertNull("dividers have no global page", layout.globalAt(3))
        assertNull(layout.globalAt(99))
        assertEquals(-1, layout.positionOfGlobal(5))
        assertEquals(-1, layout.positionOfGlobal(-1))
    }

    @Test
    fun dividersSpanTheFullRowAndPagesOneCell() {
        val layout = TankPageGridLayout.build(listOf(2, 3))

        assertEquals(4, layout.spanSize(0, 4))
        assertEquals(1, layout.spanSize(1, 4))
        assertEquals(4, layout.spanSize(3, 4))
        assertEquals(1, layout.spanSize(99, 4))
    }

    @Test
    fun negativeCountsAreTreatedAsZero() {
        val layout = TankPageGridLayout.build(listOf(-3, 1))

        assertEquals(listOf(Item.Divider(0), Item.Divider(1), Item.Page(0, 1, 0)), layout.items)
        assertEquals(1, layout.totalPages)
    }
}
