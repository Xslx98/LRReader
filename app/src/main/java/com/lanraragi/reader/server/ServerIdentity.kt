package com.lanraragi.reader.server

import com.lanraragi.reader.client.api.data.LRRServerInfo

/**
 * What a connect attempt learned about a server, for the code above transport.
 * [LRRServerInfo] stays the wire DTO; the UI sees only this. A null field means the
 * server did not send it.
 */
data class ServerIdentity(
    val name: String?,
    val version: String?,
    val versionName: String?,
    val archivesPerPage: Int,
)

internal fun LRRServerInfo.toServerIdentity(): ServerIdentity =
    ServerIdentity(
        name = name,
        version = version,
        versionName = versionName,
        archivesPerPage = archivesPerPage,
    )
