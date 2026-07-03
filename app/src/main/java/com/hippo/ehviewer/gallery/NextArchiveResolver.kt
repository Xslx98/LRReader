package com.hippo.ehviewer.gallery

import com.lanraragi.reader.client.api.LRRSearchApi
import com.lanraragi.reader.client.api.data.LRRArchive
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.domain.Archive
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient

/** One fetched window of an online search: the (Tankoubon-filtered) page plus the server's total count. */
private data class Window(val entries: List<LRRArchive>, val recordsFiltered: Int)

/**
 * Resolves "the next archive after the current one" from the published
 * [ReadingContext]. Online contexts re-fetch a window of the original search
 * at the remembered index and re-locate the anchor by arcid (drift-tolerant,
 * works on every LANraragi version — no /api/search/ids dependency). Local
 * contexts walk their in-memory snapshot.
 */
class NextArchiveResolver(private val client: OkHttpClient) {

    sealed interface NextResult {
        /** [advanced] is the context re-anchored on [archive]; publish it via [ReadingContextStore.advance] on jump. */
        data class Next(val archive: Archive, val advanced: ReadingContext) : NextResult
        data object EndOfList : NextResult
        data object NoContext : NextResult
        data class Error(val cause: Exception) : NextResult
    }

    suspend fun resolve(currentArcid: String): NextResult {
        val ctx = ReadingContextStore.currentFor(currentArcid) ?: return NextResult.NoContext
        return when (ctx) {
            is ReadingContext.OnlineSearch -> resolveOnline(ctx)
            is ReadingContext.LocalList -> resolveLocal(ctx)
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun resolveOnline(ctx: ReadingContext.OnlineSearch): NextResult {
        return try {
            // Window 1: fetch at the remembered absolute index. With no drift
            // the anchor is data[0] and its successor rides the same response.
            var window = searchWindow(ctx, ctx.anchorIndex)
            var base = ctx.anchorIndex
            var pos = window.entries.indexOfFirst { it.arcid == ctx.anchorArcid }

            if (pos < 0 && ctx.anchorIndex > 0) {
                // Drift: the anchor may have moved backwards — scan one window back.
                val backStart = (ctx.anchorIndex - window.entries.size.coerceAtLeast(1)).coerceAtLeast(0)
                val back = searchWindow(ctx, backStart)
                val backPos = back.entries.indexOfFirst { it.arcid == ctx.anchorArcid }
                if (backPos >= 0) {
                    window = back
                    base = backStart
                    pos = backPos
                }
            }

            val next: LRRArchive?
            val nextIndex: Int
            if (pos >= 0) {
                nextIndex = base + pos + 1
                next = window.entries.getOrNull(pos + 1) ?: run {
                    // Successor didn't ride this window: only worth a follow-up
                    // fetch if the server says there's more data past nextIndex.
                    if (nextIndex < window.recordsFiltered) searchWindow(ctx, nextIndex).entries.firstOrNull() else null
                }
            } else {
                // Anchor vanished from the result set entirely: treat whatever
                // now occupies its old slot as "next" (drift fallback).
                nextIndex = ctx.anchorIndex
                next = window.entries.firstOrNull()
            }
            if (next == null) return NextResult.EndOfList

            val archive = next.toArchive(ctx.sourceProfileId, ctx.sourceBaseUrl)
            NextResult.Next(archive, ctx.copy(anchorArcid = archive.arcid, anchorIndex = nextIndex))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NextResult.Error(e)
        }
    }

    private suspend fun searchWindow(ctx: ReadingContext.OnlineSearch, start: Int): Window {
        val result = LRRSearchApi.searchArchives(
            client = client,
            baseUrl = ctx.sourceBaseUrl,
            filter = ctx.filter,
            category = ctx.category,
            start = start,
            sortby = ctx.sortby,
            order = ctx.order,
            newonly = ctx.newonly,
            untaggedonly = ctx.untaggedonly,
        )
        return Window(result.data.filterNot { isTankoubonId(it.arcid) }, result.recordsFiltered)
    }

    private fun resolveLocal(ctx: ReadingContext.LocalList): NextResult {
        val idx = ctx.forwardArchives.indexOfFirst { it.arcid == ctx.anchorArcid }
        if (idx < 0) return NextResult.NoContext
        val next = ctx.forwardArchives.getOrNull(idx + 1) ?: return NextResult.EndOfList
        return NextResult.Next(
            next,
            ctx.copy(anchorArcid = next.arcid, forwardArchives = ctx.forwardArchives.drop(idx + 1)),
        )
    }
}
