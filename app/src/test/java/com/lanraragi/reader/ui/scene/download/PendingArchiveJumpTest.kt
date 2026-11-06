package com.lanraragi.reader.ui.scene.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingArchiveJumpTest {

    private val rowAt7: (String) -> Int = { if (it == "wanted") 7 else -1 }
    private val noRow: (String) -> Int = { -1 }

    @Test
    fun `a row already on the list resolves at once and only once`() {
        val jump = PendingArchiveJump().apply { request("wanted") }

        assertEquals(7, jump.resolveNow(rowAt7))
        assertNull(jump.resolveNow(rowAt7))
        assertNull(jump.resolveOnNewList(isEmpty = false, rowAt7))
    }

    @Test
    fun `a miss on the list from before the request waits for the next list`() {
        val jump = PendingArchiveJump().apply { request("wanted") }

        assertNull(jump.resolveNow(noRow))

        assertEquals(7, jump.resolveOnNewList(isEmpty = false, rowAt7))
    }

    @Test
    fun `an empty list still loading leaves the request open`() {
        val jump = PendingArchiveJump().apply { request("wanted") }

        assertNull(jump.resolveOnNewList(isEmpty = true, noRow))

        assertEquals(7, jump.resolveOnNewList(isEmpty = false, rowAt7))
    }

    @Test
    fun `a new list without the row ends the request, so no late jump follows`() {
        val jump = PendingArchiveJump().apply { request("wanted") }

        assertNull(jump.resolveOnNewList(isEmpty = false, noRow))

        // The search hiding the row is cleared and the row comes back.
        assertNull(jump.resolveOnNewList(isEmpty = false, rowAt7))
    }

    @Test
    fun `nothing requested resolves to nothing`() {
        val jump = PendingArchiveJump()

        assertNull(jump.resolveNow(rowAt7))
        assertNull(jump.resolveOnNewList(isEmpty = false, rowAt7))
    }
}
