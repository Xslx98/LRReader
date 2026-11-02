package com.lanraragi.reader.archive

import com.lanraragi.reader.domain.BARE_TAG_BUCKET
import com.lanraragi.reader.domain.TagGroup
import com.lanraragi.reader.domain.explicitlyNamespacedValues
import com.lanraragi.reader.domain.mergeTagEdits
import com.lanraragi.reader.domain.toLrrTagString
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerConnections
import kotlinx.coroutines.CancellationException

/**
 * Saves a tag edit made in the tag editor: the user changed [opened] into
 * [edited]; only that change is applied to the server's current string, on
 * the server of the object's SOURCE profile (a cross-server archive must be
 * written where it lives).
 */
class TagEditSaver(
    private val connectionFor: suspend (profileId: Long) -> ServerConnection = { ServerConnections.forProfile(it) },
) {

    /**
     * Reads the server's own string through [writer] (which bucket values
     * are really `misc:` tags, and the exact old string for the follow-up),
     * merges the edit into it, writes, then runs [TagWriter.afterWrite].
     * Throws when the write fails; a failed read only drops the merge.
     */
    @Suppress("LongParameterList")
    suspend fun save(
        serverProfileId: Long,
        writer: TagWriter,
        id: String,
        opened: List<TagGroup>,
        edited: List<TagGroup>,
    ) {
        val conn = connectionFor(serverProfileId)
        val client = conn.client
        val baseUrl = conn.baseUrl
        val raw = try {
            writer.readRaw(client, baseUrl, id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ignored: Exception) {
            null
        }
        val explicitBucket = raw?.let { explicitlyNamespacedValues(it, BARE_TAG_BUCKET) }.orEmpty()
        val openedTags = toLrrTagString(opened, explicitBucket)
        val editedTags = toLrrTagString(edited, explicitBucket)
        val oldTags = raw ?: openedTags
        // The editor was built from what the page loaded; the server
        // may have changed since (a rating saved from this very page).
        // Apply only the user's edit to the server's current string.
        val tags = if (raw != null) mergeTagEdits(openedTags, editedTags, raw) else editedTags
        writer.write(client, baseUrl, id, tags)
        writer.afterWrite(client, baseUrl, id, oldTags, tags)
    }

    companion object {
        val default: TagEditSaver by lazy { TagEditSaver() }
    }
}
