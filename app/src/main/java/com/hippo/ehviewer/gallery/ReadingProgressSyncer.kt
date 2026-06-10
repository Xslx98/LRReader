/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.gallery

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes reading-progress syncs to the LANraragi server and conflates
 * bursts: at most one PUT is in flight at a time, and rapid page turns
 * collapse so a burst of N submits costs only the in-flight send plus one
 * trailing send for the latest page — instead of racing N parallel PUTs
 * whose arrival order the server decides. A stale PUT landing last used to
 * set `progress` backwards on the server — visible as "resume jumps back a
 * few pages" together with the lastreadtime comparison (see
 * [ReadingProgressReconciler]).
 *
 * [scope] should be an application-level scope (the app ioScope): syncs
 * must survive provider stop() so the final page of a session is not lost.
 */
internal class ReadingProgressSyncer(
    private val scope: CoroutineScope,
    private val send: suspend (page0: Int) -> Unit,
) {

    private val pending = AtomicInteger(NONE)
    private val draining = AtomicBoolean(false)

    fun submit(page0: Int) {
        if (page0 < 0) return
        pending.set(page0)
        maybeDrain()
    }

    private fun maybeDrain() {
        if (pending.get() == NONE) return
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val page = pending.getAndSet(NONE)
                    if (page == NONE) break
                    try {
                        send(page)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Progress sync failed for page=$page: ${e.message}")
                    }
                }
            } finally {
                draining.set(false)
                // A submit() landing between the final getAndSet(NONE) and
                // draining.set(false) would otherwise be stranded.
                maybeDrain()
            }
        }
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
        const val TAG = "ReadingProgressSyncer"
    }
}
