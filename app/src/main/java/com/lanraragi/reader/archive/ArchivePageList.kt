package com.lanraragi.reader.archive

import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LrrFileListCache
import com.lanraragi.reader.server.ServerConnection

/** An archive's page list, through the server-keyed [LrrFileListCache]. */
object ArchivePageList {

    /**
     * Best-effort warm path. [LrrFileListCache] is keyed by the source server
     * URL, so a cross-server read hits the entry stored for that server (not
     * the active profile's). A miss falls through to a fresh fetch, which is
     * then cached; a failed fetch throws and caches nothing.
     */
    suspend fun load(conn: ServerConnection, arcid: String): Array<String> {
        val cached = LrrFileListCache.get(conn.baseUrl, arcid)
        return cached ?: LRRArchiveApi.getFileList(conn.client, conn.baseUrl, arcid).also {
            LrrFileListCache.put(conn.baseUrl, arcid, it)
        }
    }
}
