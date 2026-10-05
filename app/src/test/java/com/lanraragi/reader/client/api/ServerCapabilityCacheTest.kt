package com.lanraragi.reader.client.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ServerCapabilityCacheTest {

    private class FakeStore : ServerCapabilityCache.Store {
        val saved = HashMap<String, Set<String>>()
        var loads = 0
        override fun loadExcludedNamespaces(baseUrl: String): Set<String>? {
            loads++
            return saved[baseUrl]
        }
        override fun saveExcludedNamespaces(baseUrl: String, namespaces: Set<String>) {
            saved[baseUrl] = namespaces
        }
        override fun forget(baseUrl: String) {
            saved.remove(baseUrl)
        }
    }

    @Before
    fun setUp() {
        ServerCapabilityCache.attachStore(null)
        ServerCapabilityCache.clear()
    }

    @After
    fun tearDown() {
        ServerCapabilityCache.attachStore(null)
        ServerCapabilityCache.clear()
    }

    @Test
    fun excludedNamespaces_unknownServer_isNull() {
        assertNull(ServerCapabilityCache.excludedNamespaces("http://a"))
    }

    @Test
    fun setExcludedNamespaces_normalizesAndIsPerServer() {
        ServerCapabilityCache.setExcludedNamespaces("http://a", listOf(" Source", "date_added", ""))
        ServerCapabilityCache.setExcludedNamespaces("http://b", emptyList())

        assertEquals(setOf("source", "date_added"), ServerCapabilityCache.excludedNamespaces("http://a"))
        assertEquals(emptySet<String>(), ServerCapabilityCache.excludedNamespaces("http://b"))
    }

    @Test
    fun store_receivesWrites_andBacksReadsAfterClear() {
        // The in-memory map dies with the process; the store must be written on
        // set and consulted on a miss so a cold start still knows the exclusions.
        val store = FakeStore()
        ServerCapabilityCache.attachStore(store)

        ServerCapabilityCache.setExcludedNamespaces("http://a", listOf("source"))
        assertEquals(setOf("source"), store.saved["http://a"])

        ServerCapabilityCache.clear()
        assertEquals(setOf("source"), ServerCapabilityCache.excludedNamespaces("http://a"))
        // Second read is served from memory again — the store is hit once per miss.
        ServerCapabilityCache.excludedNamespaces("http://a")
        assertEquals(1, store.loads)
    }

    @Test
    fun describeServerInfo_summarizesWithoutNameOrMotd() {
        assertNull(ServerCapabilityCache.describeServerInfo("http://a"))
        val info = com.lanraragi.reader.client.api.data.LRRServerInfo().apply {
            name = "Alice's library"
            motd = "welcome http://10.0.0.3"
            version = "0.9.50"
            versionName = "Lovely Day"
            hasPassword = true
            excludedNamespaces = listOf("source")
        }
        ServerCapabilityCache.recordServerInfo("http://a", info)
        val line = ServerCapabilityCache.describeServerInfo("http://a")!!
        assertEquals(
            "version=0.9.50 (Lovely Day), hasPassword=true, nofun=false, debug=false, resizes=false, " +
                "tracksProgress=false, archivesPerPage=100, excludedNamespaces=1",
            line,
        )
        ServerCapabilityCache.clear()
        assertNull(ServerCapabilityCache.describeServerInfo("http://a"))
    }

    @Test
    fun forget_dropsMemoryAndPersistedFactsForThatServerOnly() {
        val store = FakeStore()
        ServerCapabilityCache.attachStore(store)
        ServerCapabilityCache.setExcludedNamespaces("http://gone", listOf("x"))
        ServerCapabilityCache.setExcludedNamespaces("http://kept", listOf("y"))
        ServerCapabilityCache.setTracksProgress("http://gone", true)

        ServerCapabilityCache.forget("http://gone")

        assertNull(ServerCapabilityCache.tracksProgress("http://gone"))
        assertNull(store.saved["http://gone"])
        assertNull(ServerCapabilityCache.excludedNamespaces("http://gone"))
        assertEquals(setOf("y"), ServerCapabilityCache.excludedNamespaces("http://kept"))
    }
}
