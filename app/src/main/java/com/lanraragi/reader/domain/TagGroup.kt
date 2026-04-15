package com.lanraragi.reader.domain

/**
 * A group of tags under a single namespace (e.g., "artist", "parody", "misc").
 */
data class TagGroup(
    val namespace: String,
    val tags: List<String>,
)
