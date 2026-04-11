package com.lanraragi.reader.client.api.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server info returned by GET /api/info.
 * This endpoint does NOT require authentication.
 *
 * Field types match the actual LANraragi API response:
 * - name, motd, version, version_name → String
 * - has_password, debug_mode, nofun_mode, server_resizes_images, server_tracks_progress → Int (0/1)
 * - archives_per_page, cache_last_cleared → Int
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
