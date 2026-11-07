package com.lanraragi.reader.archive

import com.lanraragi.reader.category.toCategory
import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRCategoryApi
import com.lanraragi.reader.domain.ArchiveDetail
import com.lanraragi.reader.domain.Category
import com.lanraragi.reader.server.ServerConnection

/**
 * What the detail page fetches for one archive. [ServerConnection] names the
 * archive's source server, which need not be the active one.
 */
object ArchiveDetailLoader {

    /** The archive's metadata, tagged with the server it came from. */
    suspend fun detail(conn: ServerConnection, arcid: String): ArchiveDetail {
        val archive = LRRArchiveApi.getArchiveMetadata(conn.client, conn.baseUrl, arcid)
        // Tag the converted Archive with the *source* profile id
        // and *source* base URL — not the active profile. Without
        // this, every cross-server detail fetch overwrites the
        // archive's serverProfileId in `_archiveDetail` and
        // `archiveDetailCache` with the active profile id, which
        // (a) flips the source-server badge to the active profile,
        // and (b) routes the next refresh to the active server,
        // 400-ing in a perpetual loop until the LRU cache evicts.
        return archive.toArchiveDetail(
            sourceProfileId = conn.profileId,
            sourceBaseUrl = conn.baseUrl,
        )
    }

    /** The categories holding this archive (per-archive endpoint, not the full list). */
    suspend fun categories(conn: ServerConnection, arcid: String): List<Category> =
        LRRCategoryApi.getArchiveCategories(conn.client, conn.baseUrl, arcid).map { it.toCategory() }
}
