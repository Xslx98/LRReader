package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRTankoubonApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tankoubon endpoints: 0-based paging, member order, global progress, partial updates. */
class TankContractTest {

    private val client get() = ContractServer.client
    private val url get() = ContractServer.baseUrl
    private val seed get() = ContractServer.seed

    @Test
    fun list_decodesTheTank_andPagingIsZeroBased() = contractTest {
        val firstPage = LRRTankoubonApi.getTankoubons(client, url)

        val tank = firstPage.result.single { it.id == seed.tank.id }
        assertEquals(seed.tank.name, tank.name)
        assertEquals(seed.tank.members, tank.archives)
        assertEquals(firstPage.result.size, firstPage.total)

        val secondPage = LRRTankoubonApi.getTankoubons(client, url, page = 1)
        assertTrue("page=1 is the second page", secondPage.result.isEmpty())
        assertEquals(firstPage.total, secondPage.total)
    }

    @Test
    fun full_pageMinusOne_returnsAllMembersWithIdsAndDataAgreeing() = contractTest {
        val full = LRRTankoubonApi.getTankoubonFull(client, url, seed.tank.id)

        assertEquals(seed.tank.id, full.result.id)
        assertEquals(seed.tank.members, full.result.archives)
        assertEquals(seed.tank.members, full.result.fullData.map { it.arcid })
        assertEquals(seed.alpha.title, full.result.fullData[0].title)
        assertEquals(seed.alpha.pages, full.result.fullData[0].pagecount)
        assertEquals(seed.bravo.pages, full.result.fullData[1].pagecount)
    }

    @Test
    fun reverseLookup_namesTheTanksContainingAnArchive() = contractTest {
        assertEquals(listOf(seed.tank.id), LRRTankoubonApi.getArchiveTankoubons(client, url, seed.alpha.id))
        assertEquals(emptyList<String>(), LRRTankoubonApi.getArchiveTankoubons(client, url, seed.big.id))
    }

    @Test
    fun hasTankThumbnail_becomesTrueOnceTheCoverIsGenerated() = contractTest {
        awaitTrue { LRRTankoubonApi.hasTankThumbnail(client, url, seed.tank.id) }
    }

    @Test
    fun lifecycle_createFillReorderRenameProgressDelete() = contractTest {
        val first = seed.charlie.id
        val second = seed.big.id
        val tankId = LRRTankoubonApi.createTankoubon(client, url, "Contract lifecycle")
        var deleted = false
        try {
            LRRTankoubonApi.addToTankoubon(client, url, tankId, first)
            LRRTankoubonApi.addToTankoubon(client, url, tankId, second)
            assertEquals(listOf(first, second), full(tankId).archives)

            LRRTankoubonApi.updateTankoubon(client, url, tankId, archives = listOf(second, first))
            assertEquals(listOf(second, first), full(tankId).archives)

            // Metadata alone must leave the membership untouched.
            LRRTankoubonApi.updateTankoubon(client, url, tankId, summary = "contract", tags = "series:lifecycle")
            LRRTankoubonApi.renameTankoubon(client, url, tankId, "Contract renamed")
            val named = full(tankId)
            assertEquals("Contract renamed", named.name)
            assertEquals("contract", named.summary)
            assertEquals("series:lifecycle", named.tags)
            assertEquals(listOf(second, first), named.archives)
            assertEquals(
                "a rename must not create a second tank",
                1,
                LRRTankoubonApi.getTankoubons(client, url).result.count { it.name.startsWith("Contract r") },
            )

            LRRTankoubonApi.updateTankProgress(client, url, tankId, seed.big.pages + 2)
            assertEquals(seed.big.pages + 2, full(tankId).progress)

            LRRTankoubonApi.removeFromTankoubon(client, url, tankId, second)
            assertEquals(listOf(first), full(tankId).archives)

            LRRTankoubonApi.deleteTankoubon(client, url, tankId)
            deleted = true
            assertFalse(LRRTankoubonApi.getTankoubons(client, url).result.any { it.id == tankId })
            assertEquals("deleting a tank keeps its archives", seed.charlie.title, titleOf(first))
        } finally {
            if (!deleted) LRRTankoubonApi.deleteTankoubon(client, url, tankId)
        }
    }

    private suspend fun full(tankId: String) = LRRTankoubonApi.getTankoubonFull(client, url, tankId).result

    private suspend fun titleOf(arcid: String) = LRRArchiveApi.getArchiveMetadata(client, url, arcid).title
}
