package com.lanraragi.reader.contract

import com.lanraragi.reader.archive.MetadataPluginService
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRPluginApi
import com.lanraragi.reader.client.api.data.LRRPluginResult
import com.lanraragi.reader.client.api.lrrJson
import com.lanraragi.reader.domain.MetadataPlugin
import com.lanraragi.reader.server.ServerConnection
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CopyArchiveTags` is the built-in plugin these run: it reads only the
 * server's own database, so no external site is involved, and its one-shot
 * argument makes the result predictable.
 */
class PluginContractTest {

    private val conn get() = ServerConnection(1L, ContractServer.baseUrl, ContractServer.client)
    private val service = MetadataPluginService()

    private suspend fun copyTags(): MetadataPlugin {
        val info = LRRPluginApi.listMetadataPlugins(ContractServer.client, ContractServer.baseUrl)
            .single { it.namespace == COPY_TAGS }
        return info.toMetadataPlugin()
    }

    private suspend fun tagsOf(arcid: String) =
        LRRArchiveApi.getArchiveMetadata(ContractServer.client, ContractServer.baseUrl, arcid).tags

    @Test
    fun list_namesBuiltInPlugins_withTheirOneshotLabel() = contractTest {
        val plugin = copyTags()

        assertEquals("Copy Archive Tags", plugin.name)
        assertTrue(plugin.version, plugin.version.isNotEmpty())
        assertTrue(plugin.description, plugin.description.isNotEmpty())
        assertTrue("a one-shot label", !plugin.oneshotLabel.isNullOrBlank())
    }

    @Test
    fun run_returnsOnlyMissingTags_andLeavesTheArchiveAlone() = contractTest {
        val seed = ContractServer.seed
        val before = tagsOf(seed.charlie.id)

        val outcome = service.run(conn, seed.charlie.id, copyTags(), seed.alpha.id)

        val suggested = outcome as MetadataPluginService.RunOutcome.Suggested
        assertEquals(
            setOf("artist:contract alpha", "contractmisc", "rating:4"),
            suggested.suggestion.newTags.toSet(),
        )
        assertEquals(before, tagsOf(seed.charlie.id))
    }

    @Test
    fun run_aPluginError_endsAsAFailureWithItsMessage() = contractTest {
        val seed = ContractServer.seed

        val outcome = service.run(conn, seed.charlie.id, copyTags(), "not an archive id")

        val failed = outcome as MetadataPluginService.RunOutcome.Failed
        assertTrue(failed.message, !failed.message.isNullOrBlank())
    }

    /** The plan chose `/queue`; this pins that its report has the shape `/use` documents. */
    @Test
    fun useAndQueue_reportTheSameFindings() = contractTest {
        val seed = ContractServer.seed
        val sync = rawUse(seed.bravo.id, seed.alpha.id)
        val queued = service.run(conn, seed.bravo.id, copyTags(), seed.alpha.id)
            as MetadataPluginService.RunOutcome.Suggested

        assertTrue(sync.success)
        assertEquals(
            sync.data!!.newTags!!.split(',').map { it.trim() }.toSet(),
            queued.suggestion.newTags.toSet(),
        )
    }

    @Test
    fun everyPluginRoute_refusesAnAnonymousClient() = contractTest {
        val anonymous = ContractServer.anonymousClient
        val base = ContractServer.baseUrl
        val arcid = ContractServer.seed.charlie.id
        assertHttpFailure(401) { LRRPluginApi.listMetadataPlugins(anonymous, base) }
        assertHttpFailure(401) { LRRPluginApi.queuePlugin(anonymous, base, arcid, COPY_TAGS, null) }
        assertHttpFailure(401) { LRRPluginApi.getMinionJob(anonymous, base, 1) }
    }

    private fun rawUse(arcid: String, arg: String): LRRPluginResult {
        val url = ContractServer.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/plugins/use")
            .addQueryParameter("id", arcid)
            .addQueryParameter("plugin", COPY_TAGS)
            .addQueryParameter("arg", arg)
            .build()
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        return ContractServer.client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            LRRPluginResult(lrrJson.parseToJsonElement(response.body!!.string()).jsonObject)
        }
    }

    private companion object {
        const val COPY_TAGS = "copy-archive-tags"
    }
}
