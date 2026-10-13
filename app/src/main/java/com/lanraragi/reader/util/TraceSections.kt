package com.lanraragi.reader.util

import android.os.Trace

/**
 * Named `android.os.Trace` sections the perf tooling reads (Perfetto on the
 * CI smoke, Macrobenchmark `TraceSectionMetric`). Kept in release: a
 * section is a microsecond-scale native call when no tracer is attached,
 * and the shipped build is `profileable`, so a field profile shows the same
 * slices a bench run does. Renaming one breaks the budget files that key on
 * it; see the perf measurement design note.
 */
object TraceSections {
    /** One reader page decode (bounds + sampled decode, both OOM attempts). */
    const val READER_DECODE = "LRR.reader.decode"

    /** One GL frame of the reader surface — invisible to HWUI frame stats. */
    const val READER_GL_FRAME = "LRR.reader.glFrame"

    /** One tile upload inside the per-frame upload budget. */
    const val READER_UPLOAD = "LRR.reader.upload"

    /** One full (non-payload) gallery list row bind. */
    const val LIST_BIND = "LRR.list.bind"
}

/**
 * Run [block] inside a trace section. Synchronous only: a section belongs to
 * the thread that opened it, so never wrap a suspension point.
 */
inline fun <T> traceSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
