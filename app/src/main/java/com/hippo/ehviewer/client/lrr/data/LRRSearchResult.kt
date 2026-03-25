package com.hippo.ehviewer.client.lrr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Search result returned by GET /api/search.
 */
@Serializable
class LRRSearchResult {
    @JvmField @SerialName("data") var data: List<LRRArchive> = emptyList()
    @JvmField @SerialName("draw") var draw: Int = 0
    @JvmField @SerialName("recordsFiltered") var recordsFiltered: Int = 0
    @JvmField @SerialName("recordsTotal") var recordsTotal: Int = 0
}
