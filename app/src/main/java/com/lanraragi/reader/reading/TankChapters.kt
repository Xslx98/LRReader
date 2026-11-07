package com.lanraragi.reader.reading

import com.lanraragi.reader.client.api.data.LRRTocEntry
import com.lanraragi.reader.domain.ReaderChapter
import com.lanraragi.reader.domain.ReaderChapterList
import com.lanraragi.reader.util.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A tank session's chapter list. Members are known from the seed, so the outer
 * level works at once and without a network. Member chapters come from one
 * heavy `/full` request, made only when the list is first opened (and again
 * after a failure when it is reopened), so a session that never opens it pays
 * nothing.
 *
 * Chapters are stored by arcid with member-local pages; [snapshot] turns them
 * into global pages against the live page map, and [refresh] must run after
 * every remap.
 */
internal class TankChapters(
    private val snapshot: (Map<String, List<LRRTocEntry>>) -> List<ReaderChapter>,
    private val fetch: suspend () -> Map<String, List<LRRTocEntry>>,
    private val publish: (ReaderChapterList) -> Unit,
) {
    private enum class Load { IDLE, LOADING, LOADED, FAILED }

    private val lock = Any()
    private var load = Load.IDLE
    private var tocs: Map<String, List<LRRTocEntry>> = emptyMap()

    /** Republishes against the live page map; the lock keeps an older list from landing last. */
    fun refresh() = synchronized(lock) {
        publish(
            ReaderChapterList(
                entries = snapshot(tocs),
                alwaysOffered = true,
                loading = load == Load.LOADING,
                failed = load == Load.FAILED,
            )
        )
    }

    /** Starts the fetch unless it is running or has already succeeded. */
    fun request(scope: CoroutineScope) {
        synchronized(lock) {
            if (load == Load.LOADING || load == Load.LOADED) return
            load = Load.LOADING
        }
        refresh()
        scope.launch {
            val result = suspendRunCatching { fetch() }
            synchronized(lock) {
                result.onSuccess { tocs = it }
                load = if (result.isSuccess) Load.LOADED else Load.FAILED
            }
            refresh()
        }
    }
}
