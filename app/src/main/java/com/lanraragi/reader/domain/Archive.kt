package com.lanraragi.reader.domain

/**
 * Shared domain model representing a LANraragi archive.
 * Immutable data class — the canonical representation used across
 * UI and business logic layers.
 *
 * Uses the natural LANraragi identifier [arcid] (String) rather than
 * the legacy hashed gid (Long).
 */
data class Archive(
    val arcid: String,
    val title: String,
    val tags: Map<String, List<String>>,
    val pagecount: Int,
    val progress: Int,
    val extension: String,
    val filename: String,
    val thumbnailUrl: String,
    val rating: Float,
    val isnew: Boolean,
    val lastreadtime: Long,
    val summary: String?,
    val serverProfileId: Long,
)
