package com.hippo.ehviewer.ui.gallery

import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRClientProvider
import com.lanraragi.reader.client.api.LRRStampApi
import com.lanraragi.reader.client.api.resolveSourceBaseUrl

/** Server calls for one archive's stamps; pages 1-indexed (server semantics). */
interface StampsBackend {
    suspend fun stampedPages(): List<Int>
    suspend fun stampsByPage(page1: Int): List<LRRStampApi.StampData>
    suspend fun add(page1: Int, content: String, position: String): String
    suspend fun update(stampId: String, content: String?, position: String?)
    suspend fun delete(stampId: String)
}

/**
 * Real backend. Routes every call by the archive's SOURCE profile
 * (multi-profile red line), same as [com.hippo.ehviewer.ui.scene.gallery.detail.DeleteArchiveHelper].
 */
class LRRStampsBackend(
    private val arcid: String,
    private val serverProfileId: Long,
) : StampsBackend {

    private suspend fun baseUrl(): String = resolveSourceBaseUrl(
        serverProfileId,
        ServiceRegistry.dataModule.profileLookupCache,
    )

    override suspend fun stampedPages(): List<Int> =
        LRRStampApi.getStampedPages(LRRClientProvider.getClient(), baseUrl(), arcid)

    override suspend fun stampsByPage(page1: Int): List<LRRStampApi.StampData> =
        LRRStampApi.getStampsByPage(LRRClientProvider.getClient(), baseUrl(), arcid, page1)

    override suspend fun add(page1: Int, content: String, position: String): String =
        LRRStampApi.addStamp(LRRClientProvider.getClient(), baseUrl(), arcid, page1, content, position)

    override suspend fun update(stampId: String, content: String?, position: String?) =
        LRRStampApi.updateStamp(LRRClientProvider.getClient(), baseUrl(), stampId, content, position)

    override suspend fun delete(stampId: String) =
        LRRStampApi.deleteStamp(LRRClientProvider.getClient(), baseUrl(), stampId)
}
