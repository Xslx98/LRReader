package com.lanraragi.reader.cover

import java.io.InputStream

/**
 * A per-request place a cover's bytes live besides the shared disk cache —
 * the `.thumb` file in a downloaded archive's directory, so its cover still
 * shows offline and survives cache eviction. Read first; filled from the
 * disk cache or the network on a miss ([StoredCoverFetcher]).
 *
 * Every method runs on a loader I/O thread, never on the main thread.
 */
interface CoverStore {

    /** False when the store cannot be used for this request; the load then
     * behaves like a plain cover load. */
    fun isEnabled(): Boolean

    /** The stored bytes, or null when nothing is stored. Must not create files. */
    fun open(): InputStream?

    /** Stores [input]'s bytes; false when that failed. */
    fun save(input: InputStream): Boolean

    /** Drops the stored bytes (a no-op when there are none). */
    fun remove()
}
