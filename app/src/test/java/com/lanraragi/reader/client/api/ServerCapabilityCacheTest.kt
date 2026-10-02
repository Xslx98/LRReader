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
}
