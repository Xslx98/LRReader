package com.lanraragi.reader.client.api.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server info returned by `GET /api/info` (operationId `getServerInfo`).
 * This endpoint does NOT require authentication.
 *
 * Field types follow the OpenAPI v0.9.6 ServerInfo schema. Spec-defined
 * fields not consumed by this client today are omitted intentionally —
 * `ignoreUnknownKeys = true` on [com.lanraragi.reader.client.api.lrrJson]
 * means future server fields are tolerated without a client release.
 *
 * Spec fields not yet surfaced in UI (add when needed): `version_desc`,
 * `authenticated_progress`, `total_pages_read`, `total_archives`,
 * `excluded_namespaces`.
 */
@Serializable
class LRRServerInfo {
    @JvmField @SerialName("name") var name: String? = null
    @JvmField @SerialName("motd") var motd: String? = null
    @JvmField @SerialName("version") var version: String? = null
    @JvmField @SerialName("version_name") var versionName: String? = null
    @JvmField @SerialName("has_password") var hasPassword: Boolean = false
    @JvmField @SerialName("debug_mode") var debugMode: Boolean = false
    @JvmField @SerialName("nofun_mode") var nofunMode: Boolean = false
    @JvmField @SerialName("archives_per_page") var archivesPerPage: Int = 100
    @JvmField @SerialName("server_resizes_images") var serverResizesImages: Boolean = false
    @JvmField @SerialName("server_tracks_progress") var serverTracksProgress: Boolean = false
    @JvmField @SerialName("cache_last_cleared") var cacheLastCleared: Long = 0

    override fun toString(): String =
        "LRRServerInfo{name='$name', version='$version ($versionName)', hasPassword=$hasPassword}"
}
