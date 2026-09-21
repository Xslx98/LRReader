package com.lanraragi.reader.gallery

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * History member-row redirect (spec 2026-09-21 §5): a row whose archive
 * belongs to a tankoubon resumes the tank; no tank, an unreachable server
 * or a malformed answer fall back to the ordinary detail page (null).
 */
class HistoryTankRedirectTest {

    @Test
    fun `first containing tank wins`() = runTest {
        assertEquals("TANK_1", HistoryTankRedirect.memberTankId { listOf("TANK_1", "TANK_2") })
    }

    @Test
    fun `no tank yields null`() = runTest {
        assertNull(HistoryTankRedirect.memberTankId { emptyList() })
    }

    @Test
    fun `fetch failure yields null`() = runTest {
        assertNull(HistoryTankRedirect.memberTankId { throw IOException("offline") })
        assertNull(HistoryTankRedirect.memberTankId { throw IllegalStateException("bad body") })
    }
}
