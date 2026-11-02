package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.archive.TagWriter
import com.lanraragi.reader.client.api.LRRTankoubonApi
import okhttp3.OkHttpClient

/**
 * A tankoubon's own tags: `PUT /api/tankoubons/{id}` with only
 * `metadata.tags` (whole-string replace; a stray `archives` key would
 * rewrite membership).
 */
object TankTagWriter : TagWriter {
    override suspend fun write(client: OkHttpClient, baseUrl: String, id: String, tags: String) {
        LRRTankoubonApi.updateTankoubon(client, baseUrl, id, tags = tags)
    }

    override suspend fun readRaw(client: OkHttpClient, baseUrl: String, id: String): String? =
        LRRTankoubonApi.getTankoubonFull(client, baseUrl, id).result.tags
}
