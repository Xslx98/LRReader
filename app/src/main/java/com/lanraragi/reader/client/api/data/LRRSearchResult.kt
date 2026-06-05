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
     * Convert all result archives to domain models, dropping any Tankoubon
     * entries (15-char TANK_ ids) the server may fold in when groupby_tanks is
     * enabled — the archive pipeline can't render them.
     */
    fun toArchiveList(): List<Archive> = data.filterNot { isTankoubonId(it.arcid) }.map { it.toArchive() }
}
