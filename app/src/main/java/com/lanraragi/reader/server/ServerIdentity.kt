package com.lanraragi.reader.server

import com.lanraragi.reader.client.api.data.LRRServerInfo

/**
 * What a connect attempt learned about a server, for the code above transport.
 * [LRRServerInfo] stays the wire DTO; the UI sees only this. Null fields mean the
 * server did not send them, and the caller picks the fallback.
 */
data class ServerIdentity(
    val name: String?,
    val version: String?,
    val versionName: String?,
    val archivesPerPage: Int,
)

internal fun LRRServerInfo.toServerIdentity(): ServerIdentity =
    ServerIdentity(name, version, versionName, archivesPerPage)
