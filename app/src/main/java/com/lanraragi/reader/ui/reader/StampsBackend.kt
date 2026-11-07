package com.lanraragi.reader.ui.reader

import com.lanraragi.reader.client.api.LRRStampApi
import com.lanraragi.reader.domain.ReaderStamp
import com.lanraragi.reader.server.ServerConnections

/** Server calls for one archive's stamps; pages 1-indexed (server semantics). */
interface StampsBackend {
    suspend fun stampedPages(): List<Int>
    suspend fun stampsByPage(page1: Int): List<ReaderStamp>
    suspend fun add(page1: Int, content: String, position: String): String
    suspend fun update(stampId: String, content: String?, position: String?)
    suspend fun delete(stampId: String)
}

/**
 * Real backend. Routes every call by the archive's SOURCE profile
 * (multi-profile red line), same as [com.lanraragi.reader.ui.scene.gallery.detail.DeleteArchiveHelper].
 */
class LRRStampsBackend(
    private val arcid: String,
    private val serverProfileId: Long,
) : StampsBackend {

    private suspend fun conn() = ServerConnections.forProfile(serverProfileId)

    override suspend fun stampedPages(): List<Int> =
        conn().let { LRRStampApi.getStampedPages(it.client, it.baseUrl, arcid) }

    override suspend fun stampsByPage(page1: Int): List<ReaderStamp> =
        conn().let { LRRStampApi.getStampsByPage(it.client, it.baseUrl, arcid, page1) }

    override suspend fun add(page1: Int, content: String, position: String): String =
        conn().let { LRRStampApi.addStamp(it.client, it.baseUrl, arcid, page1, content, position) }

    override suspend fun update(stampId: String, content: String?, position: String?) =
        conn().let { LRRStampApi.updateStamp(it.client, it.baseUrl, stampId, content, position) }

    override suspend fun delete(stampId: String) =
        conn().let { LRRStampApi.deleteStamp(it.client, it.baseUrl, stampId) }
}
