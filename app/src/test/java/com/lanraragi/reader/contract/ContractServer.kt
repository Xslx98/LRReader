package com.lanraragi.reader.contract

import com.lanraragi.reader.client.api.LRRHttpException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The throwaway server these tests write to, from `scripts/ci/contract/seed.py`.
 * Missing env vars throw, so `-PlrrContract` on a mis-wired job fails rather
 * than going green having tested nothing. See that directory's README.
 */
internal object ContractServer {
    val baseUrl: String by lazy { requireLoopback(requireEnv("LRR_CONTRACT_URL")) }
    val apiKey: String by lazy { requireEnv("LRR_CONTRACT_KEY") }
    val seed: ContractSeed by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(File(requireEnv("LRR_CONTRACT_SEED")).readText())
    }

    /** No key: what a client without credentials sees. */
    val anonymousClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Sends the key in the wire format the server's `is_logged_in_api` expects.
     * This is plain JVM, where the production helper's android.util.Base64 is a
     * stub returning null; [ContractAuthTest] proves that helper against the server.
     */
    val client: OkHttpClient by lazy {
        val token = Base64.getEncoder().encodeToString(apiKey.toByteArray())
        anonymousClient.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            }
            .build()
    }

    private fun requireEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("$name is not set: run seed.py first (scripts/ci/contract/README.md)")
}

/** These tests write and delete, so anything but this machine is refused. */
internal fun requireLoopback(url: String): String {
    val host = url.toHttpUrlOrNull()?.host ?: throw IllegalArgumentException("Not a URL: $url")
    require(host in LOOPBACK_HOSTS) { "Contract tests only run against a loopback server, got host: $host" }
    return url
}

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
private const val HTTP_TIMEOUT_S = 60L
private const val TEST_DEADLINE_MS = 240_000L
private const val POLL_MS = 500L

@Serializable
internal data class ContractSeed(
    val archives: Map<String, SeededArchive>,
    val spare: SeededArchive,
    val tank: SeededTank,
    val categories: SeededCategories,
    val stamp: SeededStamp,
) {
    val alpha get() = archives.getValue("alpha")
    val bravo get() = archives.getValue("bravo")
    val charlie get() = archives.getValue("charlie")
    val big get() = archives.getValue("big")
}

@Serializable
internal data class SeededArchive(
    val id: String = "",
    val title: String,
    val file: String,
    val pages: Int,
    val tags: String = "",
    @SerialName("page_rgb") val pageRgb: List<List<Int>>,
    val toc: List<SeededToc> = emptyList(),
)

@Serializable
internal data class SeededToc(val page: Int, val name: String)

@Serializable
internal data class SeededTank(val id: String, val name: String, val members: List<String>)

@Serializable
internal data class SeededCategories(val static: SeededStatic, val dynamic: SeededDynamic)

@Serializable
internal data class SeededStatic(val id: String, val name: String, val members: List<String>)

@Serializable
internal data class SeededDynamic(val id: String, val name: String, val search: String)

@Serializable
internal data class SeededStamp(
    val id: String,
    val archive: String,
    val page: Int,
    val content: String,
    val position: String,
)

/** Real time, because the server does real work: runTest would skip the polling delays. */
internal fun contractTest(block: suspend () -> Unit) = runBlocking { withTimeout(TEST_DEADLINE_MS) { block() } }

/** Polls until [read] gives a value; the enclosing [contractTest] deadline bounds it. */
internal suspend fun <T : Any> awaitValue(read: suspend () -> T?): T {
    while (true) {
        read()?.let { return it }
        delay(POLL_MS)
    }
}

internal suspend fun awaitTrue(condition: suspend () -> Boolean) {
    awaitValue { condition().takeIf { it } }
}

/** A plain GET for what the client's API classes do not expose, such as an omitted parameter. */
internal fun rawGet(url: String): ByteArray {
    val request = Request.Builder().url(url).get().build()
    return ContractServer.client.newCall(request).execute().use { response ->
        assertEquals("GET $url", 200, response.code)
        response.body!!.bytes()
    }
}

internal inline fun assertHttpFailure(expectedCode: Int, block: () -> Unit): LRRHttpException {
    try {
        block()
    } catch (e: LRRHttpException) {
        assertEquals(expectedCode, e.code)
        return e
    }
    fail("expected HTTP $expectedCode")
    error("unreachable")
}

/** Which of the seeded [colours] the middle of [image] shows; JPEG shifts colours slightly. */
internal fun nearestPage(image: ByteArray, colours: List<List<Int>>): Int {
    val rgb = centreRgb(image)
    return colours.indices.minBy { i -> colours[i].indices.sumOf { c -> abs(colours[i][c] - rgb[c]) } }
}

/**
 * Reflection, because unit tests compile against android.jar, which has no
 * javax.imageio, although the JVM running them does.
 */
private fun centreRgb(image: ByteArray): List<Int> {
    val decoded = Class.forName("javax.imageio.ImageIO")
        .getMethod("read", InputStream::class.java)
        .invoke(null, ByteArrayInputStream(image))
        ?: error("Not a decodable image (${image.size} bytes)")
    val type = decoded.javaClass
    val x = type.getMethod("getWidth").invoke(decoded) as Int / 2
    val y = type.getMethod("getHeight").invoke(decoded) as Int / 2
    val pixel = type.getMethod("getRGB", Int::class.java, Int::class.java).invoke(decoded, x, y) as Int
    return listOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
}
