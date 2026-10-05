package com.lanraragi.reader.client.api

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit C41: JSON bodies are decoded from the stream and cut off at a byte cap. */
class DecodeJsonBodyTest {

    private fun response(body: ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url("http://lrr.test/api/database/stats").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body)
        .build()

    /** Body with no declared length, so only the counted read can enforce the cap. */
    private fun chunked(json: String): ResponseBody =
        Buffer().writeUtf8(json).asResponseBody("application/json".toMediaType(), contentLength = -1)

    @Test
    fun decodes_list_from_stream() {
        val json = """[{"id":"SET_0000000001","name":"A","archives":["x"]}]"""
        val cats = decodeJsonBody<List<com.lanraragi.reader.client.api.data.LRRCategory>>(response(chunked(json)))
        assertEquals(1, cats.size)
        assertEquals(listOf("x"), cats[0].archives)
    }

    @Test
    fun body_exactly_at_cap_decodes() {
        val json = """[1,2,3]"""
        val values = decodeJsonBody<List<Int>>(response(chunked(json)), maxBytes = json.length.toLong())
        assertEquals(listOf(1, 2, 3), values)
    }

    @Test
    fun declared_length_over_cap_is_rejected_before_reading() {
        val json = """[1,2,3,4,5,6,7,8,9]"""
        val body = json.toResponseBody("application/json".toMediaType())
        val e = assertThrows(LRRBodyTooLargeException::class.java) {
            decodeJsonBody<List<Int>>(response(body), maxBytes = 4)
        }
        assertEquals(4L, e.limit)
    }

    @Test
    fun streamed_body_over_cap_is_cut_off() {
        val json = "[" + (1..2000).joinToString(",") + "]"
        assertThrows(LRRBodyTooLargeException::class.java) {
            decodeJsonBody<List<Int>>(response(chunked(json)), maxBytes = 1024)
        }
    }

    @Test
    fun too_large_body_is_not_retried() = runTest {
        var calls = 0
        val e = runCatching {
            retryOnFailure(maxRetries = 2) {
                calls++
                throw LRRBodyTooLargeException(MAX_JSON_BODY_BYTES)
            }
        }.exceptionOrNull()
        assertTrue(e is LRRBodyTooLargeException)
        assertEquals(1, calls)
    }
}
