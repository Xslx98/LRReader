package com.lanraragi.reader.ui.scene.gallery.detail

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * [TagEditDialog.TagWriter] targets (spec 2026-09-22 §4.4): the same
 * dialog saves an archive's metadata or a tankoubon's own tags, and the
 * tank writer must send ONLY `metadata.tags` — a stray `archives` key
 * would rewrite membership.
 */
class TagEditDialogWriterTest {

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
    fun tankoubonWriter_putsOnlyMetadataTags() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1}"""))

        TagEditDialog.tankoubonWriter.write(client, baseUrl, "TANK_1700000000", "artist:foo, rating:⭐⭐")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/tankoubons/TANK_1700000000", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertFalse("membership must stay untouched", body.containsKey("archives"))
        val metadata = body.getValue("metadata").jsonObject
        assertEquals(setOf("tags"), metadata.keys)
        assertEquals("artist:foo, rating:⭐⭐", metadata.getValue("tags").jsonPrimitive.content)
    }

    @Test
    fun archiveWriter_afterWrite_rematerializesEveryContainingTank() = runTest {
        val arcid = "b".repeat(40)
        val other = "a".repeat(40)
        // 1) which tanks contain the archive  2) that tank's /full  3) the tag PUT
        server.enqueue(MockResponse().setBody("""{"tankoubons":["TANK_1700000000"]}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"id":"TANK_1700000000","name":"Tank","summary":null,""" +
                    """"tags":"artist:a, parody:p, hand:written","progress":0,"archives":["$other","$arcid"],""" +
                    """"full_data":[{"arcid":"$other","title":"t","tags":"artist:a","pagecount":1,"progress":0,""" +
                    """"lastreadtime":0,"isnew":"false","extension":"zip","filename":"a.zip","summary":""},""" +
                    """{"arcid":"$arcid","title":"t","tags":"artist:a, new:n","pagecount":1,"progress":0,""" +
                    """"lastreadtime":0,"isnew":"false","extension":"zip","filename":"b.zip","summary":""}]},""" +
                    """"total":1,"filtered":1}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"success":1}"""))

        TagEditDialog.archiveWriter.afterWrite(client, baseUrl, arcid, oldTags = "artist:a, parody:p", newTags = "artist:a, new:n")

        assertEquals("/api/archives/$arcid/tankoubons", server.takeRequest().path)
        assertEquals("/api/tankoubons/TANK_1700000000/full?page=-1", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val body = Json.parseToJsonElement(put.body.readUtf8()).jsonObject
        assertEquals("artist:a, hand:written, new:n", body.getValue("metadata").jsonObject.getValue("tags").jsonPrimitive.content)
    }

    @Test
    fun archiveWriter_afterWrite_isSilentWithoutTankoubonRoutes() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        TagEditDialog.archiveWriter.afterWrite(client, baseUrl, "b".repeat(40), "x:1", "x:2")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun archiveWriter_putsArchiveMetadata() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":1}"""))
        val arcid = "a".repeat(40)

        TagEditDialog.archiveWriter.write(client, baseUrl, arcid, "artist:foo")

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/archives/$arcid/metadata", req.requestUrl?.encodedPath)
        assertEquals("artist:foo", req.requestUrl?.queryParameter("tags"))
    }
}
