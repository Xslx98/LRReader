package com.lanraragi.reader.archive

import com.lanraragi.reader.client.api.LRRMiscApi
import com.lanraragi.reader.server.ServerConnection

/** Asks a LANraragi server to download an archive from a URL itself. */
object UrlDownloads {

    /** Queues [url] on the server of [conn], in no category; returns the server's job id. */
    suspend fun enqueue(conn: ServerConnection, url: String): Int =
        LRRMiscApi.downloadUrl(conn.client, conn.baseUrl, url, null)
}
