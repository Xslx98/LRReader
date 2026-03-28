package com.hippo.ehviewer.client.lrr

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

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/categories", req.path)
    }

    @Test
    fun createCategory_sendsFormBody() = runTest {
        server.enqueue(MockResponse().setBody("""{"category_id":"new_cat","operation":"create_category","success":1}"""))

        val catId = LRRCategoryApi.createCategory(client, baseUrl, "NewCat", search = "tag:test", pinned = true)
        assertEquals("new_cat", catId)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/categories", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=NewCat"))
        assertTrue(body.contains("pinned=true"))
    }

    @Test
    fun addToCategory_url() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"add_to_category","success":1}"""))

        LRRCategoryApi.addToCategory(client, baseUrl, "cat1", "arc1")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/categories/cat1/arc1", req.path)
    }

    @Test
    fun removeFromCategory_sendsDelete() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"remove_from_category","success":1}"""))

        LRRCategoryApi.removeFromCategory(client, baseUrl, "cat1", "arc1")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/categories/cat1/arc1", req.path)
    }

    @Test
    fun deleteCategory_sendsDelete() = runTest {
        server.enqueue(MockResponse().setBody("""{"operation":"delete_category","success":1}"""))

        LRRCategoryApi.deleteCategory(client, baseUrl, "cat1")

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/categories/cat1", req.path)
    }
}
