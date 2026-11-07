package com.lanraragi.reader.archive

import android.util.Log
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.LRRPluginApi
import com.lanraragi.reader.client.api.data.LRRMinionJob
import com.lanraragi.reader.domain.MetadataPlugin
import com.lanraragi.reader.domain.PluginTagChanges
import com.lanraragi.reader.domain.PluginTagSuggestion
import com.lanraragi.reader.server.ServerCapabilityCache
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Runs a server's metadata plugins on one archive and writes what the user
 * confirmed. [run] never writes: `/api/plugins/queue` leaves the archive alone,
 * so [apply] is the only path to a write. [ServerConnection] says which server:
 * the archive's source. Abandoning a run is harmless: the job ends on the
 * server and its result is never read.
 *
 * @param background the scope [apply] writes on, so a closing screen cannot
 *   cut it in half; null = the app's ioScope, resolved at first use
 */
class MetadataPluginService(
    private val tagWriter: TagWriter = ArchiveTagWriter,
    background: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val background: CoroutineScope by lazy { background ?: ServiceRegistry.coroutineModule.ioScope }

    /** How a plugin run ended, when the server answered at all. */
    sealed interface RunOutcome {
        class Suggested(val suggestion: PluginTagSuggestion) : RunOutcome

        /** The plugin (or its job) reported a failure; [message] is the server's, if any. */
        class Failed(val message: String?) : RunOutcome

        /** Still unfinished at the poll cap. The job may yet finish on the server. */
        object TimedOut : RunOutcome
    }

    /**
     * The server's metadata plugins. When the server is known to want a key and
     * this profile has none, throws the 401 it would answer, without asking.
     * Unknown (no `/api/info` seen yet) is not a refusal: the request finds out.
     */
    suspend fun plugins(conn: ServerConnection): List<MetadataPlugin> {
        if (withContext(ioDispatcher) { isKeyMissing(conn) }) throw LRRHttpException(HTTP_UNAUTHORIZED)
        return LRRPluginApi.listMetadataPlugins(conn.client, conn.baseUrl).map { it.toMetadataPlugin() }
    }

    private fun isKeyMissing(conn: ServerConnection): Boolean {
        // Profile 0 is legacy data on the active server (ServerConnections.forProfile).
        val profile = if (conn.profileId == 0L) ServerSession.getActiveProfileId() else conn.profileId
        return ServerCapabilityCache.hasPassword(conn.baseUrl) == true &&
            ServerSession.getApiKeyForProfile(profile) == null
    }

    /**
     * Queues the plugin and polls its job until it ends. Throws on transport
     * and HTTP failures; cancelling stops the polling only.
     */
    suspend fun run(conn: ServerConnection, arcid: String, plugin: MetadataPlugin, arg: String?): RunOutcome {
        val job = LRRPluginApi.queuePlugin(conn.client, conn.baseUrl, arcid, plugin.namespace, arg)
        repeat(MAX_POLLS) {
            outcomeOf(LRRPluginApi.getMinionJob(conn.client, conn.baseUrl, job))?.let { return it }
            delay(POLL_INTERVAL_MS)
        }
        return RunOutcome.TimedOut
    }

    /**
     * Unions the confirmed tags into the server's current string (never the
     * one the page loaded, which may be stale) and writes them with the
     * confirmed title and summary, then runs the tank follow-up. A failed read
     * writes nothing. Runs on [background]; throws what the write threw, such
     * as a 423 for a locked archive, and logs it for a caller that has left.
     *
     * @return false when there was nothing left to write: the server already
     *   holds those tags, or keeps its own rating
     */
    suspend fun apply(conn: ServerConnection, arcid: String, changes: PluginTagChanges): Boolean {
        if (changes.isEmpty) return false
        return background.async {
            try {
                write(conn, arcid, changes)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Plugin tag write failed: ${e.javaClass.simpleName}")
                throw e
            }
        }.await()
    }

    private suspend fun write(conn: ServerConnection, arcid: String, changes: PluginTagChanges): Boolean {
        val oldTags = if (changes.tags.isEmpty()) {
            null
        } else {
            tagWriter.readRaw(conn.client, conn.baseUrl, arcid)
                ?: throw IOException("The archive's current tags could not be read")
        }
        val additions = oldTags?.let { PluginTagMerge.additions(it, changes.tags) }.orEmpty()
        val newTags = if (additions.isEmpty()) null else PluginTagMerge.union(oldTags.orEmpty(), additions)
        if (newTags == null && changes.title == null && changes.summary == null) return false
        LRRArchiveApi.updateMetadata(
            conn.client, conn.baseUrl, arcid,
            title = changes.title, tags = newTags, summary = changes.summary,
        )
        if (oldTags != null && newTags != null) {
            tagWriter.afterWrite(conn.client, conn.baseUrl, arcid, oldTags, newTags)
        }
        return true
    }

    /** Null while the job has not ended. */
    private fun outcomeOf(job: LRRMinionJob): RunOutcome? = when (job.state) {
        STATE_FINISHED -> {
            val result = job.pluginResult()
            val data = result?.data
            if (result == null || !result.success || data == null) {
                RunOutcome.Failed(result?.error ?: job.failureText())
            } else {
                RunOutcome.Suggested(
                    PluginTagSuggestion(PluginTagMerge.parseNewTags(data.newTags), data.titleText, data.summaryText)
                )
            }
        }
        STATE_FAILED -> RunOutcome.Failed(job.failureText() ?: job.pluginResult()?.error)
        else -> null
    }

    private companion object {
        const val TAG = "MetadataPluginService"
        const val HTTP_UNAUTHORIZED = 401
        const val STATE_FINISHED = "finished"
        const val STATE_FAILED = "failed"
        const val POLL_INTERVAL_MS = 1500L
        const val MAX_POLLS = 120 // 180 s at the default interval.
    }
}
