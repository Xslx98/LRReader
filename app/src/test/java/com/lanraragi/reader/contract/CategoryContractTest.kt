package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.LRRServerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Category endpoints, including tanks as category members. */
class CategoryContractTest {

    private val client get() = ContractServer.client
    private val url get() = ContractServer.baseUrl
    private val seed get() = ContractServer.seed

    @Test
    fun list_decodesStaticAndDynamicCategories() = contractTest {
        val categories = LRRCategoryApi.getCategories(client, url)

        val static = categories.single { it.id == seed.categories.static.id }
        assertEquals(seed.categories.static.name, static.name)
        assertFalse(static.isDynamic())
        assertEquals(seed.categories.static.members.toSet(), static.archives.toSet())

        val dynamic = categories.single { it.id == seed.categories.dynamic.id }
        assertTrue(dynamic.isDynamic())
        assertEquals(seed.categories.dynamic.search, dynamic.search)
    }

    @Test
    fun archiveCategories_listsOnlyStaticCategoriesHoldingTheArchive() = contractTest {
        val holding = LRRCategoryApi.getArchiveCategories(client, url, seed.alpha.id)
        assertEquals(listOf(seed.categories.static.id), holding.map { it.id })
        assertEquals(emptyList<String?>(), LRRCategoryApi.getArchiveCategories(client, url, seed.bravo.id).map { it.id })
    }

    /** 0.9.81 started accepting a TANK_ id here; the client falls back to the full list before that. */
    @Test
    fun tankAsCategoryMember_directRouteByVersion_andTheFallbackEitherWay() = contractTest {
        val version = LRRServerApi.getServerInfo(client, url).version.orEmpty()
        if (isAtLeast0981(version)) {
            val direct = LRRCategoryApi.getArchiveCategories(client, url, seed.tank.id)
            assertEquals(listOf(seed.categories.static.id), direct.map { it.id })
        } else {
            assertHttpFailure(400) { LRRCategoryApi.getArchiveCategories(client, url, seed.tank.id) }
        }
        val containing = LRRCategoryApi.categoriesContaining(client, url, seed.tank.id)
        assertEquals(listOf(seed.categories.static.id), containing.map { it.id })
    }

    @Test
    fun lifecycle_createPinAddRemoveRenameDelete() = contractTest {
        val id = LRRCategoryApi.createCategory(client, url, "Contract lifecycle", pinned = true)
        var deleted = false
        try {
            val created = LRRCategoryApi.getCategories(client, url).single { it.id == id }
            assertTrue("pinned: ${created.pinned}", created.isPinned())

            LRRCategoryApi.addToCategory(client, url, id, seed.big.id)
            assertTrue(id in LRRCategoryApi.getArchiveCategories(client, url, seed.big.id).map { it.id })

            LRRCategoryApi.removeFromCategory(client, url, id, seed.big.id)
            assertFalse(id in LRRCategoryApi.getArchiveCategories(client, url, seed.big.id).map { it.id })

            LRRCategoryApi.updateCategory(client, url, id, "Contract renamed")
            assertEquals("Contract renamed", LRRCategoryApi.getCategories(client, url).single { it.id == id }.name)

            LRRCategoryApi.deleteCategory(client, url, id)
            deleted = true
            assertFalse(LRRCategoryApi.getCategories(client, url).any { it.id == id })
        } finally {
            if (!deleted) LRRCategoryApi.deleteCategory(client, url, id)
        }
    }

    private fun isAtLeast0981(version: String): Boolean {
        val parts = version.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val (major, minor, patch) = parts + listOf(0, 0, 0)
        return compareValuesBy(Triple(major, minor, patch), Triple(0, 9, 81), { it.first }, { it.second }, { it.third }) >= 0
    }
}
