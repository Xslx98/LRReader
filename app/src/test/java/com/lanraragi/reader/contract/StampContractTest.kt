package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRStampApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun pageOutOfRange_isRefusedWithTheServerMessage() = contractTest {
        val failure = assertHttpFailure(400) {
            LRRStampApi.addStamp(client, url, seed.charlie.id, seed.charlie.pages + 1, "nope", "10,20")
        }
        assertTrue("serverError: ${failure.serverError}", !failure.serverError.isNullOrBlank())
    }
}
