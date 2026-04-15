package com.lanraragi.reader.domain

/**
 * Extended archive information for the detail view.
 * Composes [Archive] with structured tag groups and file metadata.
 */
data class ArchiveDetail(
    val archive: Archive,
    val tagGroups: List<TagGroup>,
    val language: String?,
    val size: String?,
)
