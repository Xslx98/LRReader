package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRDatabaseApi
import com.lanraragi.reader.client.api.LRRSearchApi
import com.lanraragi.reader.client.api.LRRServerApi
import com.lanraragi.reader.client.api.data.LRRSearchResult
import com.lanraragi.reader.client.api.data.LRRTagStat
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.client.api.lrrJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search, stats and server info: what every list screen is built on. */
class SearchContractTest {

    private val client get() = ContractServer.client
    private val url get() = ContractServer.baseUrl
    private val seed get() = ContractServer.seed

    private suspend fun search(
        filter: String? = null,
        category: String? = null,
        start: Int = 0,
        sortby: String? = null,
        order: String? = null,
        groupbyTanks: Boolean = false,
    ) = LRRSearchApi.searchArchives(
        client, url, filter, category, start, sortby, order, newonly = false, groupbyTanks = groupbyTanks,
    )

    private fun LRRSearchResult.ids() = data.map { it.arcid }

    @Test
    fun groupbyTanksFalse_listsEveryArchiveAndNoTank() = contractTest {
        val result = search()

        assertTrue(result.ids().containsAll(seed.archives.values.map { it.id }))
        assertTrue(result.ids().none(::isTankoubonId))
        assertEquals(result.data.size, result.recordsFiltered)
        assertEquals(result.data.size, result.recordsTotal)
        val alpha = result.data.single { it.arcid == seed.alpha.id }
        assertEquals(seed.alpha.title, alpha.title)
        assertEquals(seed.alpha.pages, alpha.pagecount)
    }

    @Test
    fun groupbyTanksTrue_foldsMembersIntoTheTank() = contractTest {
        val result = search(groupbyTanks = true)
        val ids = result.ids()

        assertTrue(ids.toString(), seed.tank.id in ids)
        assertTrue(ids.toString(), ids.none { it in seed.tank.members })
        assertTrue(ids.toString(), seed.charlie.id in ids)
        val tank = result.data.single { it.arcid == seed.tank.id }
        assertEquals(seed.tank.name, tank.title)
        assertEquals("a tank's pagecount sums its members", seed.alpha.pages + seed.bravo.pages, tank.pagecount)
    }

    @Test
    fun omittingGroupbyTanks_foldsByDefault_whichIsWhyTheClientAlwaysSendsIt() = contractTest {
        val body = rawGet("$url/api/search").decodeToString()
        val ids = lrrJson.decodeFromString<LRRSearchResult>(body).ids()
        assertTrue(ids.toString(), seed.tank.id in ids)
    }

    @Test
    fun exactTagFilter_returnsOnlyTheMatch() = contractTest {
        assertEquals(listOf(seed.alpha.id), search(filter = "artist:contract alpha$").ids())
    }

    @Test
    fun categoryFilter_appliesStaticMembershipAndDynamicSearch() = contractTest {
        assertEquals(
            setOf(seed.alpha.id, seed.charlie.id),
            search(category = seed.categories.static.id).ids().toSet(),
        )
        assertEquals(
            setOf(seed.bravo.id, seed.charlie.id),
            search(category = seed.categories.dynamic.id).ids().toSet(),
        )
    }

    @Test
    fun sortAndStart_orderAndOffsetTheResults() = contractTest {
        val ascending = search(sortby = "title", order = "asc").data.map { it.title }
        assertEquals(ascending.sortedBy { it.lowercase() }, ascending)
        assertEquals(ascending.reversed(), search(sortby = "title", order = "desc").data.map { it.title })
        assertEquals(ascending.drop(1), search(sortby = "title", order = "asc", start = 1).data.map { it.title })
    }

    @Test
    fun random_returnsTheRequestedCountOfArchives() = contractTest {
        val result = LRRSearchApi.getRandomArchives(client, url, filter = null, count = 2)
        assertEquals(2, result.data.size)
        assertTrue(result.ids().none(::isTankoubonId))
        assertTrue(result.data.all { it.pagecount > 0 })
    }

    @Test
    fun tagStats_decodeNamespacedAndUnNamespacedTags() = contractTest {
        val stats = LRRDatabaseApi.getTagStats(client, url)

        val misc = stats.single { it.text == "contractmisc" }
        assertTrue("namespace: '${misc.namespace}'", misc.namespace.isNullOrEmpty())
        val series = stats.single { it.text == "contract" && it.namespace == "series" }
        assertEquals(2, series.weight)
    }

    @Test
    fun tagStats_clientAsksTheServerToDropExcludedNamespaces() = contractTest {
        val excluded = LRRServerApi.getServerInfo(client, url).excludedNamespaces
        assertTrue(excluded.toString(), "date_added" in excluded)

        val unfiltered = lrrJson.decodeFromString<List<LRRTagStat>>(
            rawGet("$url/api/database/stats").decodeToString(),
        )
        assertTrue("control: the tag exists", unfiltered.any { it.namespace == "date_added" })
        assertFalse(LRRDatabaseApi.getTagStats(client, url).any { it.namespace == "date_added" })
    }

    @Test
    fun serverInfo_decodesTheFieldsTheClientGatesOn() = contractTest {
        val info = LRRServerApi.getServerInfo(client, url)

        assertTrue("version: ${info.version}", info.version.orEmpty().matches(Regex("""\d+\.\d+\.\d+.*""")))
        assertFalse("versionName", info.versionName.isNullOrBlank())
        assertFalse("name", info.name.isNullOrBlank())
        assertTrue("hasPassword", info.hasPassword)
        assertTrue("serverTracksProgress", info.serverTracksProgress)
        assertEquals(100, info.archivesPerPage)
        assertTrue("cacheLastCleared", info.cacheLastCleared > 0)
    }
}
