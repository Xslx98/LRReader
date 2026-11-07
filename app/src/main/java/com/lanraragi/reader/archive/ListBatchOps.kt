package com.lanraragi.reader.archive

import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.TankFillDispatcher
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveDeletedEvent
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.tankoubon.TankRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The server writes of a gallery-list batch, one item at a time. The caller
 * owns ordering, concurrency, per-item fault isolation and the lifetime of
 * the batch ([ListBatchWrites]); every call names its server.
 */
object ListBatchOps {

    /**
     * Applies [op] to one selected row: an archive, or a tank in tank mode.
     * Throws when the write fails. A deleted archive is announced with
     * [ArchiveDeletedEvent]; a sorted tank's previous member order lands in
     * [undoOrders] (absent when it was already sorted).
     */
    suspend fun execute(
        op: BatchOp,
        conn: ServerConnection,
        archive: Archive,
        tanks: TankRepository,
        undoOrders: MutableMap<String, List<String>>,
    ) {
        val client = conn.client
        val baseUrl = conn.baseUrl
        when (op) {
            is BatchOp.AddToCategory ->
                LRRCategoryApi.addToCategory(client, baseUrl, op.categoryId, archive.arcid)
            is BatchOp.AddToTankoubon ->
                LRRTankoubonApi.addToTankoubon(client, baseUrl, op.tankId, archive.arcid)
            BatchOp.ClearNew -> LRRArchiveApi.clearNewFlag(client, baseUrl, archive.arcid)
            BatchOp.DeleteArchives -> {
                LRRArchiveApi.deleteArchive(client, baseUrl, archive.arcid)
                AppEventBus.postArchiveDeletedEvent(ArchiveDeletedEvent(archive.arcid))
            }
            BatchOp.DownloadTanks -> {
                // Resolve the members, then fire the fill on the main thread
                // (download-manager state lookups are main-thread only).
                val m = tanks.members(conn, archive.arcid)
                val context = ServiceRegistry.appModule.getContext()
                if (m.members.isEmpty()) throw IOException(context.getString(R.string.error_empty))
                withContext(Dispatchers.Main) {
                    val downloadManager = ServiceRegistry.dataModule.downloadManager
                    val plan = TankFillDispatcher.plan(m.members) { downloadManager.getDownloadState(it) }
                    TankFillDispatcher.dispatch(
                        context, downloadManager, plan, m.tankId, m.name, archive.serverProfileId, m.memberIdsInOrder,
                    )
                }
            }
            BatchOp.SortTanks ->
                tanks.autoSort(conn, archive.arcid)?.let { undoOrders[archive.arcid] = it }
            BatchOp.DeleteTanks -> tanks.delete(conn, archive.arcid)
            BatchOp.Download -> error("use runBatchDownload")
        }
    }

    /** Sets [tankId]'s cover to its global page 1 on [conn]'s server. */
    suspend fun seedTankCover(conn: ServerConnection, tankId: String) {
        LRRTankoubonApi.updateTankThumbnail(conn.client, conn.baseUrl, tankId, globalPage1 = 1)
    }
}
