package com.lanraragi.reader.archive

import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.TankTagSyncFailedEvent
import com.lanraragi.reader.tankoubon.TankTagOutbox
import okhttp3.OkHttpClient

/**
 * Where a saved tag string goes. The tag editor edits ONE tag string; the
 * writer decides which server object owns it (an archive's metadata, or a
 * tankoubon's own `metadata.tags`, spec 2026-09-22 §4.4).
 */
interface TagWriter {
    suspend fun write(client: OkHttpClient, baseUrl: String, id: String, tags: String)

    /**
     * The object's current tag string as the server stores it, or null
     * when it cannot be read. Tells bare tags apart from explicit
     * `misc:` ones, which the grouped display model cannot.
     */
    suspend fun readRaw(client: OkHttpClient, baseUrl: String, id: String): String? = null

    /**
     * Follow-up after a successful [write]; [oldTags] is the server's
     * string before the edit. Must not throw past cancellation.
     */
    @Suppress("LongParameterList")
    suspend fun afterWrite(client: OkHttpClient, baseUrl: String, id: String, oldTags: String, newTags: String) {}
}

/**
 * An archive's tags: `PUT /api/archives/{id}/metadata`, then (spec
 * 2026-09-22 §5.2) every tankoubon containing the archive re-materializes
 * its own tags with this member's old → new contribution (rule 3), through
 * [TankTagOutbox] so a failure is retried later. Servers without the
 * tankoubon routes just skip the follow-up.
 */
object ArchiveTagWriter : TagWriter {
    override suspend fun write(client: OkHttpClient, baseUrl: String, id: String, tags: String) {
        LRRArchiveApi.updateMetadata(client, baseUrl, id, tags = tags)
    }

    override suspend fun readRaw(client: OkHttpClient, baseUrl: String, id: String): String =
        LRRArchiveApi.getArchiveMetadata(client, baseUrl, id).tags

    override suspend fun afterWrite(client: OkHttpClient, baseUrl: String, id: String, oldTags: String, newTags: String) {
        // A failed follow-up is kept and retried (audit 2026-10-04 REL-24):
        // the member's old contribution exists only now.
        if (!TankTagOutbox.applyOnServer(client, baseUrl, id, oldTags, newTags)) {
            AppEventBus.postTankTagSyncFailedEvent(
                TankTagSyncFailedEvent("", "", TankTagSyncFailedEvent.Kind.TAGS_PENDING)
            )
        }
    }
}
