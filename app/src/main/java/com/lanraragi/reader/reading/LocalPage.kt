package com.lanraragi.reader.reading

import java.io.File

/**
 * Whether a page file on disk counts as landed, for every reader path that
 * chooses between a local copy and a fetch (reader cache, download directory,
 * tank member caches). One definition: before, "present" was
 * `exists() && length() > 16` in some places and "too small" was
 * `length() < 16` in others, so a 16-byte stub passed the post-fetch check
 * yet was fetched again on every open.
 */
internal object LocalPage {

    /**
     * True when [file] is a regular file longer than a truncated stub
     * ([ReaderPageCache.MIN_IMAGE_SIZE]). Call it once per attempt and act on
     * that answer: a second look can see a concurrent writer's rename.
     */
    fun probe(file: File): Boolean = file.isFile && file.length() > ReaderPageCache.MIN_IMAGE_SIZE
}
