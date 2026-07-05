package com.lanraragi.reader.client.api.data

import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.domain.Archive
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

    /**
     * Convert result entries to domain models. Tankoubon entries (TANK_ ids the
     * server folds in when groupby_tanks is on) are dropped unless [includeTanks]
     * — then they become display-only pseudo-Archives carrying the tank
     * thumbnail route (see [LRRArchive.toTankArchive]). A null [tankBaseUrl]
     * drops tanks even when included: without a base URL there is no renderable
     * thumbnail route (mirrors toArchive's null-URL tolerance).
     */
    fun toArchiveList(
        includeTanks: Boolean = false,
        tankProfileId: Long = -1L,
        tankBaseUrl: String? = null,
    ): List<Archive> = data.mapNotNull { entry ->
        when {
            !isTankoubonId(entry.arcid) -> entry.toArchive()
            includeTanks && tankBaseUrl != null -> entry.toTankArchive(tankProfileId, tankBaseUrl)
            else -> null
        }
    }
}
