package com.hippo.ehviewer.gallery

import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRSearchApi
import com.lanraragi.reader.client.api.data.LRRArchive
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.domain.Archive
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient

/**
 * One fetched window of an online search: the RAW page (Tankoubon entries kept
 * in — indexes must line up with what the browse list showed) plus the
 * server's total count.
 */
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
            is ReadingContext.Tankoubon -> resolveTank(ctx)
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

            // "Next" = the first NON-TANK entry after the anchor. Tanks ride the
            // raw window for index arithmetic but are never a reading target.
            val next: LRRArchive?
            val nextIndex: Int
            if (pos >= 0) {
                val tail = window.entries.drop(pos + 1)
                val rel = tail.indexOfFirst { !isTankoubonId(it.arcid) }
                if (rel >= 0) {
                    next = tail[rel]
                    nextIndex = base + pos + 1 + rel
                } else {
                    // No non-tank successor rode this window: walk forward.
                    val scan = scanForward(ctx, base + window.entries.size, window.recordsFiltered)
                    next = scan?.first
                    nextIndex = scan?.second ?: (base + pos + 1)
                }
            } else {
                // Anchor vanished: whatever occupies its old slot (first non-tank) is "next".
                val rel = window.entries.indexOfFirst { !isTankoubonId(it.arcid) }
                next = if (rel >= 0) window.entries[rel] else null
                nextIndex = if (rel >= 0) base + rel else ctx.anchorIndex
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

    /** Walk forward window-by-window for the first non-tank entry (≤[MAX_FORWARD_WINDOWS] fetches). */
    private suspend fun scanForward(
        ctx: ReadingContext.OnlineSearch,
        firstStart: Int,
        recordsFiltered: Int,
    ): Pair<LRRArchive, Int>? {
        var start = firstStart
        repeat(MAX_FORWARD_WINDOWS) {
            if (start >= recordsFiltered) return null
            val w = searchWindow(ctx, start)
            if (w.entries.isEmpty()) return null
            val rel = w.entries.indexOfFirst { !isTankoubonId(it.arcid) }
            if (rel >= 0) return w.entries[rel] to (start + rel)
            start += w.entries.size
        }
        return null
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
            groupbyTanks = ctx.groupbyTanks,
        )
        return Window(result.data, result.recordsFiltered) // RAW: tanks stay in for indexing
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveTank(ctx: ReadingContext.Tankoubon): NextResult {
        val idx = ctx.orderedMemberIds.indexOf(ctx.anchorArcid)
        if (idx < 0) return NextResult.NoContext
        val nextId = ctx.orderedMemberIds.getOrNull(idx + 1) ?: return NextResult.EndOfList
        return try {
            val lrr = LRRArchiveApi.getArchiveMetadata(client, ctx.sourceBaseUrl, nextId)
            // multi-profile red line: explicit source context on the mapper
            val archive = lrr.toArchive(ctx.sourceProfileId, ctx.sourceBaseUrl)
            NextResult.Next(archive, ctx.copy(anchorArcid = archive.arcid))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NextResult.Error(e)
        }
    }

    private companion object {
        /** Upper bound on follow-up window fetches when scanning past a run of tanks. */
        const val MAX_FORWARD_WINDOWS = 3
    }
}
