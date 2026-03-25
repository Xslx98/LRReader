/*
 * STUB (LANraragi: E-Hentai torrent download removed)
 */
package com.hippo.ehviewer.download

import android.content.Context
import android.os.Handler
import okhttp3.OkHttpClient

/**
 * DownloadTorrentManager — STUB.
 * E-Hentai torrent downloading has been removed.
 */
class DownloadTorrentManager private constructor(private val okHttpClient: OkHttpClient) {

    fun download(url: String, saveDir: String, name: String, handler: Handler?, context: Context) {
        // No-op: E-Hentai torrent download removed
    }

    companion object {
        @JvmStatic
        fun get(okHttpClient: OkHttpClient): DownloadTorrentManager {
            return DownloadTorrentManager(okHttpClient)
        }

        @JvmStatic
        val sDCardPath: String get() = ""

        @JvmStatic
        fun getNameFromUrl(url: String): String = ""
    }
}