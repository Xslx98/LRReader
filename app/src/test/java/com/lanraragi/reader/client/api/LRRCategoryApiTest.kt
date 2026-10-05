package com.lanraragi.reader.client.api

import com.lanraragi.reader.awaitRequest
import com.lanraragi.reader.client.api.*
import com.lanraragi.reader.client.api.data.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LRRCategoryApiTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
        client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getCategories_parsesList() = runTest {
        server.enqueue(MockResponse().setBody("""[
            {"id":"c1","name":"Favorites","archives":["a1"],"pinned":"1","search":""},
            {"id":"c2","name":"Dynamic","archives":[],"pinned":"0","search":"artist:foo"}
        ]"""))

        val cats = LRRCategoryApi.getCategories(client, baseUrl)
        assertEquals(2, cats.size)
        assertEquals("Favorites", cats[0].name)
        assertTrue(cats[0].isPinned())
        assertFalse(cats[0].isDynamic())
        assertTrue(cats[1].isDynamic())

        val req = server.awaitRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/categories", req.path)
    }

    @Test
    fun getArchiveCategories_usesPerArchiveEndpoint() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"find_arc_categories","success":1,"categories":[
            {"id":"SET_0000000001","name":"Favorites","archives":["$ARCID"],"pinned":0,"search":""}
        ]}"""))

        val cats = LRRCategoryApi.getArchiveCategories(client, baseUrl, ARCID)
        assertEquals(listOf("Favorites"), cats.map { it.name })

        val req = server.awaitRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/archives/$ARCID/categories", req.path)
    }

    @Test
    fun categoriesContaining_tankRejectedWith400_fallsBackToFullList() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setBody("""[
            {"id":"SET_0000000001","name":"Has","archives":["$TANK"],"pinned":"0","search":""},
            {"id":"SET_0000000002","name":"Other","archives":["$ARCID"],"pinned":"0","search":""},
            {"id":"SET_0000000003","name":"Dyn","archives":["$TANK"],"pinned":"0","search":"x"}
        ]"""))

        val cats = LRRCategoryApi.categoriesContaining(client, baseUrl, TANK)
        assertEquals(listOf("Has"), cats.map { it.name })
        assertEquals("/api/archives/$TANK/categories", server.awaitRequest().path)
        assertEquals("/api/categories", server.awaitRequest().path)
    }

    @Test
    fun categoriesContaining_tankAuthFailure_doesNotFallBack() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            LRRCategoryApi.categoriesContaining(client, baseUrl, TANK)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(401, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun categoriesContaining_archiveRejectedWith400_isNotMasked() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        try {
            LRRCategoryApi.categoriesContaining(client, baseUrl, ARCID)
            fail("Should have thrown")
        } catch (e: LRRHttpException) {
            assertEquals(400, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun createCategory_sendsFormBody() = runTest {
        server.enqueue(MockResponse().setBody("""{"category_id":"new_cat","operation":"create_category","success":1}"""))

        val catId = LRRCategoryApi.createCategory(client, baseUrl, "NewCat", search = "tag:test", pinned = true)
        assertEquals("new_cat", catId)

        val req = server.awaitRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/categories", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=NewCat"))
        assertTrue(body.contains("pinned=true"))
    }

    @Test
    fun addToCategory_url() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"add_to_category","success":1}"""))

        LRRCategoryApi.addToCategory(client, baseUrl, "SET_aaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        val req = server.awaitRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/categories/SET_aaaaaaaaaa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", req.path)
    }

    @Test
    fun removeFromCategory_sendsDelete() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"remove_from_category","success":1}"""))

        LRRCategoryApi.removeFromCategory(client, baseUrl, "SET_aaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        val req = server.awaitRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/categories/SET_aaaaaaaaaa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", req.path)
    }

    @Test
    fun addToCategory_acceptsTankoubonId() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"add_to_category","success":1}"""))

        LRRCategoryApi.addToCategory(client, baseUrl, "SET_aaaaaaaaaa", "TANK_1700000000")

        val req = server.awaitRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/categories/SET_aaaaaaaaaa/TANK_1700000000", req.path)
    }

    @Test
    fun removeFromCategory_acceptsTankoubonId() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"remove_from_category","success":1}"""))

        LRRCategoryApi.removeFromCategory(client, baseUrl, "SET_aaaaaaaaaa", "TANK_1700000000")

        val req = server.awaitRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/categories/SET_aaaaaaaaaa/TANK_1700000000", req.path)
    }

    @Test
    fun addToCategory_rejectsMalformedTankoubonId() = runTest {
        try {
            LRRCategoryApi.addToCategory(client, baseUrl, "SET_aaaaaaaaaa", "TANK_x")
            fail("expected validation failure")
        } catch (e: LRRClientValidationException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun deleteCategory_sendsDelete() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"delete_category","success":1}"""))

        LRRCategoryApi.deleteCategory(client, baseUrl, "SET_aaaaaaaaaa")

        val req = server.awaitRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/categories/SET_aaaaaaaaaa", req.path)
    }

    private companion object {
        const val ARCID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TANK = "TANK_1700000000"
    }
}
