package com.lanraragi.reader.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit C48 / SEC-09: the arcid reaches mkdirs() before any API validates it. */
class ReaderPageCacheDirNameTest {

    @Test
    fun wellFormedIdsKeepTheirDirectory() {
        val arcid = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(arcid, ReaderPageCache.cacheDirName(arcid))
        assertEquals("TANK_1700000000", ReaderPageCache.cacheDirName("TANK_1700000000"))
    }

    @Test
    fun traversalIdsAreHashedIntoOneSegment() {
        val name = ReaderPageCache.cacheDirName("../../shared_prefs/x")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
        assertTrue(name.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun emptyIdIsHashedToo() {
        assertTrue(ReaderPageCache.cacheDirName("").matches(Regex("[0-9a-f]{40}")))
    }
}
