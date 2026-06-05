package com.lanraragi.reader.client.api.data

import kotlinx.serialization.Serializable

/**
 * Represents a single tag statistic from LANraragi's
 * GET /api/database/stats endpoint.
 *
 * @property namespace the tag namespace (e.g., "artist", "parody", "date_added"),
 *   or null for un-namespaced ("misc") tags. The spec types this field as
 *   `[string, "null"]` and returns null for tags like "artbook"; a non-null
 *   String here throws during deserialization and silently kills the tag cache.
 * @property text the tag value (e.g., "some_artist")
 * @property weight the number of archives that have this tag
 */
@Serializable
data class LRRTagStat(
    val namespace: String? = null,
    val text: String = "",
    val weight: Int = 0
)
