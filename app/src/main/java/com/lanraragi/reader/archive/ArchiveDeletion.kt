package com.lanraragi.reader.archive

import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveDeletedEvent
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.ServerConnections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Deletes an archive from the server that owns it and tells the app. Runs on
 * [scope], a process scope: once the DELETE is sent, a rotation or a closed
 * page must not cancel it before [ArchiveDeletedEvent] is posted (lists
 * would keep the row).
 */
class ArchiveDeletion(
    private val scope: CoroutineScope = ServiceRegistry.coroutineModule.ioScope,
    private val connectionFor: suspend (profileId: Long) -> ServerConnection = { ServerConnections.forProfile(it) },
) {

    /** Starts [delete] on the service's scope; [onResult] runs on that scope's thread. */
    fun deleteAsync(serverProfileId: Long, arcid: String, onResult: (Result<Unit>) -> Unit): Job = scope.launch {
        val result = try {
            delete(serverProfileId, arcid)
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
        onResult(result)
    }

    /**
     * Sends the DELETE to the server of [serverProfileId] (the archive's
     * SOURCE profile, not the active one: arcids are content hashes, so the
     * same file can exist on several servers under one id), then posts
     * [ArchiveDeletedEvent]. Throws when the delete fails; no event then.
     */
    suspend fun delete(serverProfileId: Long, arcid: String) {
        val conn = connectionFor(serverProfileId)
        LRRArchiveApi.deleteArchive(conn.client, conn.baseUrl, arcid)
        AppEventBus.postArchiveDeletedEvent(ArchiveDeletedEvent(arcid))
    }

    companion object {
        val default: ArchiveDeletion by lazy { ArchiveDeletion() }
    }
}
