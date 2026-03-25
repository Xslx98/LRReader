package com.hippo.ehviewer.client.lrr.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LANraragi category (static or dynamic).
 * Static categories have an empty `search` field and manually managed archives.
 * Dynamic categories have a non-empty `search` field that auto-matches archives.
 */
@Serializable
class LRRCategory {
    @JvmField @SerialName("id") var id: String? = null
    @JvmField @SerialName("name") var name: String? = null
    @JvmField @SerialName("archives") var archives: List<String> = emptyList()
    @JvmField @SerialName("pinned") @Serializable(with = FlexibleStringSerializer::class) var pinned: String? = null
    @JvmField @SerialName("search") var search: String? = null

    /** @return true if this is a dynamic category (search-based). */
    fun isDynamic(): Boolean = !search.isNullOrEmpty()

    /** @return true if this category is pinned. */
    fun isPinned(): Boolean = "1" == pinned
}
