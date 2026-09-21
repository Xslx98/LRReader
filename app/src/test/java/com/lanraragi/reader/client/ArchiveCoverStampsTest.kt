package com.lanraragi.reader.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveCoverStampsTest {

    private val a = "a".repeat(40)
    private val url = "http://lrr.local/api/archives/$a/thumbnail"

    @Before
    fun setUp() {
        ArchiveCoverStamps.resetForTest(emptyMap())
    }

    @Test
    fun `untouched archives keep today's key and url`() {
        assertEquals("preview:large:$a:0", LRRCacheKeyFactory.getThumbKey(a))
        assertEquals(url, ArchiveCoverStamps.bust(url, a))
    }

    @Test
    fun `bump changes the key and appends a ts parameter`() {
        ArchiveCoverStamps.bump(a)
        val stamp = ArchiveCoverStamps.get(a)
        assertTrue(stamp > 0L)
        assertEquals("preview:large:$a:$stamp", LRRCacheKeyFactory.getThumbKey(a))
        assertEquals("$url?ts=$stamp", ArchiveCoverStamps.bust(url, a))
        assertEquals("$url?x=1&ts=$stamp", ArchiveCoverStamps.bust("$url?x=1", a))
    }

    @Test
    fun `every bump is a new stamp`() {
        ArchiveCoverStamps.bump(a)
        val first = ArchiveCoverStamps.get(a)
        ArchiveCoverStamps.bump(a)
        assertNotEquals(first, ArchiveCoverStamps.get(a))
        assertTrue(ArchiveCoverStamps.get(a) > first)
    }
}
