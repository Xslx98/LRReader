package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRStampApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Stamp endpoints: 1-indexed pages, opaque ids, and the in-body error path. */
class StampContractTest {

    private val client get() = ContractServer.client
    private val url get() = ContractServer.baseUrl
    private val seed get() = ContractServer.seed

    @Test
    fun seededStamp_decodesFromBothReadRoutes() = contractTest {
        val stamp = seed.stamp
        assertEquals(listOf(stamp.page), LRRStampApi.getStampedPages(client, url, stamp.archive))

        val read = LRRStampApi.getStampsByPage(client, url, stamp.archive, stamp.page).single()
        assertEquals(stamp.id, read.id)
        assertEquals(stamp.content, read.content)
        assertEquals(stamp.position, read.position)
    }

    @Test
    fun lifecycle_addUpdateDelete() = contractTest {
        val arcid = seed.charlie.id
        val id = LRRStampApi.addStamp(client, url, arcid, 2, "first", "10,20")
        var deleted = false
        try {
            assertTrue(2 in LRRStampApi.getStampedPages(client, url, arcid))

            LRRStampApi.updateStamp(client, url, id, content = "second", position = "30,40")
            val updated = LRRStampApi.getStampsByPage(client, url, arcid, 2).single { it.id == id }
            assertEquals("second", updated.content)
            assertEquals("30,40", updated.position)

            LRRStampApi.deleteStamp(client, url, id)
            deleted = true
            assertFalse(LRRStampApi.getStampsByPage(client, url, arcid, 2).any { it.id == id })
        } finally {
            if (!deleted) LRRStampApi.deleteStamp(client, url, id)
        }
    }

    /** The server answers this one 200 with `success: 0`, so only the body says it failed. */
    @Test
    fun pageOutOfRange_surfacesTheErrorFromA200Envelope() = contractTest {
        val page = seed.charlie.pages + 1
        try {
            LRRStampApi.addStamp(client, url, seed.charlie.id, page, "nope", "10,20")
            fail("expected the out-of-range page to be refused")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), "out of range" in e.message.orEmpty())
        }
    }
}
