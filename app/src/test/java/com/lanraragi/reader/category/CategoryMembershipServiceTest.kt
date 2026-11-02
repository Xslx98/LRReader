package com.lanraragi.reader.category

import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.server.ServerConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [CategoryMembershipService]: the dialog sees only static categories of the
 * archive's SOURCE server, and applying writes exactly the changed rows, in
 * order, stopping at the first failure.
 */
class CategoryMembershipServiceTest {

    private val arcid = "a".repeat(40)

    private lateinit var server: MockWebServer
    private lateinit var conn: ServerConnection
    private val writes = CopyOnWriteArrayList<String>()

    @Volatile
    private var failingCategory: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path == "/api/categories" -> MockResponse().setBody(
                        """[{"id":"SET_1000000001","name":"Fav","archives":["$arcid"],"pinned":"0","search":""},""" +
                            """{"id":"SET_1000000002","name":"Dyn","archives":[],"pinned":"0","search":"artist:x"},""" +
                            """{"id":"SET_1000000003","name":"Later","archives":[],"pinned":"1"}]"""
                    )
                    request.method == "PUT" && path == "/api/categories" -> {
                        writes.add("${request.method} $path")
                        MockResponse().setBody("""{"success":1,"category_id":"SET_1000000009"}""")
                    }
                    else -> {
                        writes.add("${request.method} $path")
                        val failing = failingCategory
                        if (failing != null && path.startsWith("/api/categories/$failing/")) {
                            MockResponse().setResponseCode(423)
                        } else {
                            MockResponse().setBody("""{"success":1}""")
                        }
                    }
                }
            }
        }
        server.start()
        val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
        conn = ServerConnection(3L, server.url("").toString().removeSuffix("/"), client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loadStatic_usesTheProfilesServer_andDropsSearchCategories() = runTest {
        val asked = mutableListOf<Long>()
        val service = CategoryMembershipService { asked += it; conn }

        val loaded = service.loadStatic(3L)

        assertEquals(listOf(3L), asked)
        assertEquals(conn, loaded.conn)
        assertEquals(
            listOf(StaticCategory("SET_1000000001", "Fav", listOf(arcid)), StaticCategory("SET_1000000003", "Later", emptyList())),
            loaded.categories,
        )
    }

    @Test
    fun apply_writesOnlyChangedRows_andReturnsCheckedNames() = runTest {
        val cats = listOf(
            StaticCategory("SET_1000000001", "Fav", listOf(arcid)),
            StaticCategory("SET_1000000003", "Later", emptyList()),
            StaticCategory("SET_1000000004", null, emptyList()),
            StaticCategory("SET_1000000005", "Kept", listOf(arcid)),
        )

        val names = CategoryMembershipService { conn }.apply(
            conn, arcid, cats,
            original = listOf(true, false, false, true),
            checked = listOf(false, true, true, true),
        )

        assertEquals(listOf("Later", "", "Kept"), names)
        assertEquals(
            listOf(
                "DELETE /api/categories/SET_1000000001/$arcid",
                "PUT /api/categories/SET_1000000003/$arcid",
                "PUT /api/categories/SET_1000000004/$arcid",
            ),
            writes,
        )
    }

    @Test
    fun apply_stopsAtTheFirstFailure() = runTest {
        failingCategory = "SET_1000000001"
        val cats = listOf(StaticCategory("SET_1000000001", "A", emptyList()), StaticCategory("SET_1000000003", "B", emptyList()))

        try {
            CategoryMembershipService { conn }.apply(conn, arcid, cats, listOf(false, false), listOf(true, true))
            fail("expected the 423 to propagate")
        } catch (e: LRRHttpException) {
            assertEquals(423, e.code)
        }
        assertEquals(listOf("PUT /api/categories/SET_1000000001/$arcid"), writes)
    }

    @Test
    fun createWith_createsThenAdds() = runTest {
        val id = CategoryMembershipService { conn }.createWith(conn, "New", arcid)

        assertEquals("SET_1000000009", id)
        assertEquals(2, writes.size)
        assertTrue(writes[0], writes[0] == "PUT /api/categories")
        assertEquals("PUT /api/categories/SET_1000000009/$arcid", writes[1])
    }
}
