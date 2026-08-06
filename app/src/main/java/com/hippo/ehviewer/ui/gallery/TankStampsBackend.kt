package com.hippo.ehviewer.ui.gallery

import com.hippo.ehviewer.gallery.TankGalleryProvider
import com.lanraragi.reader.client.api.LRRStampApi
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Stamps backend for a whole-tank composite session: the controller keeps
 * thinking in one page space, this backend translates GLOBAL 1-indexed
 * pages through the provider's LIVE map to each member's own
 * [LRRStampsBackend]. Stamp ids are server-scoped (update/delete carry no
 * arcid), so id-only ops delegate to any member backend.
 *
 * The stamped-page index fans out over every member (bounded
 * concurrency); one member answering 404 fails the whole refresh with
 * that 404 — correct, stamps support is a server-wide capability and the
 * controller then disables the feature for the session.
 */
internal class TankStampsBackend(
    private val provider: TankGalleryProvider,
    private val profileId: Long,
) : StampsBackend {

    private val backends = ConcurrentHashMap<String, LRRStampsBackend>()

    private fun backendFor(arcid: String): LRRStampsBackend =
        backends.computeIfAbsent(arcid) { LRRStampsBackend(it, profileId) }

    private fun anyBackend(): LRRStampsBackend {
        val arcid = provider.memberArcids().firstOrNull()
            ?: throw IOException("tank has no members")
        return backendFor(arcid)
    }

    override suspend fun stampedPages(): List<Int> = coroutineScope {
        val semaphore = Semaphore(INDEX_CONCURRENCY)
        provider.memberArcids()
            .map { arcid ->
                async {
                    semaphore.withPermit { arcid to backendFor(arcid).stampedPages() }
                }
            }
            .awaitAll()
            .flatMap { (arcid, pages1) ->
                pages1.mapNotNull { page1 ->
                    provider.globalPageOf(arcid, page1 - 1)?.plus(1)
                }
            }
    }

    override suspend fun stampsByPage(page1: Int): List<LRRStampApi.StampData> {
        val (arcid, local0) = provider.locateMember(page1 - 1) ?: return emptyList()
        return backendFor(arcid).stampsByPage(local0 + 1)
    }

    override suspend fun add(page1: Int, content: String, position: String): String {
        val (arcid, local0) = provider.locateMember(page1 - 1)
            ?: throw IOException("global page $page1 is unmapped")
        return backendFor(arcid).add(local0 + 1, content, position)
    }

    override suspend fun update(stampId: String, content: String?, position: String?) =
        anyBackend().update(stampId, content, position)

    override suspend fun delete(stampId: String) = anyBackend().delete(stampId)

    private companion object {
        const val INDEX_CONCURRENCY = 4
    }
}
