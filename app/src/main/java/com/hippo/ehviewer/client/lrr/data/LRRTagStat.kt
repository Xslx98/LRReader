package com.hippo.ehviewer.client.lrr.data

import kotlinx.serialization.Serializable

/**
 * Represents a single tag statistic from LANraragi's
 * GET /api/database/stats endpoint.
 *
 * @property namespace the tag namespace (e.g., "artist", "parody", "date_added")
 * @property text the tag value (e.g., "some_artist")
 * @property weight the number of archives that have this tag
 */
@Serializable
data class LRRTagStat(
    val namespace: String = "",
    val text: String = "",
    val weight: Int = 0
)
