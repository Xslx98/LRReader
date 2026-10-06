package com.lanraragi.reader.dao

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Ends a Room observation quietly when the database cannot be read, instead
 * of throwing into the collector's scope.
 *
 * A Room flow opens the database on first collection. When that open fails
 * (a file written by a newer app version, a migration that throws) the
 * exception used to reach `lifecycleScope` / `viewModelScope`, which have no
 * handler, and killed the process before the boot-failure dialog could show
 * (audit 2026-10-06d STAB-02 AVD smoke). The boot profile loader is the one
 * place that reports such a failure (AppModule.bootProfileLoadError); every
 * observation only logs and stops emitting, so the screen keeps its current
 * (usually empty) state. Cancellation still propagates.
 */
fun <T> Flow<T>.endOnDbFailure(tag: String, what: String): Flow<T> = catch { e ->
    if (e is CancellationException || e !is Exception) throw e
    Log.e(tag, "$what: database unavailable, observation ended", e)
}
