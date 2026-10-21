package com.lanraragi.reader.cover

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toUri
import okio.Buffer
import okio.source

/** Cover request data for a load backed by a [CoverStore]. */
class StoredCover(val url: String, val store: CoverStore)

/**
 * Serves a [StoredCover]: the store's bytes when it has them, otherwise the
 * regular network fetch (which reads and writes the shared disk cache under
 * the request's disk cache key), copied into the store on the way through.
 * A disabled store degrades to the plain fetch. Mirrors Conaco's
 * DataContainer flow, which downloaded archives relied on for offline covers.
 */
class StoredCoverFetcher(
    private val data: StoredCover,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val store = data.store
        if (!store.isEnabled()) return fetchUrl()

        store.open()?.use { input ->
            val buffer = Buffer().apply { writeAll(input.source()) }
            return SourceFetchResult(
                source = ImageSource(buffer, options.fileSystem),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }

        val result = fetchUrl() as? SourceFetchResult ?: return null
        val buffer = Buffer()
        result.source.use { it.source().readAll(buffer) }
        if (!store.save(buffer.copy().inputStream())) store.remove()
        return SourceFetchResult(
            source = ImageSource(buffer, options.fileSystem),
            mimeType = result.mimeType,
            dataSource = result.dataSource,
        )
    }

    private suspend fun fetchUrl(): FetchResult? =
        imageLoader.components.newFetcher(data.url.toUri(), options, imageLoader)
            ?.first?.fetch()

    class Factory : Fetcher.Factory<StoredCover> {
        override fun create(data: StoredCover, options: Options, imageLoader: ImageLoader): Fetcher =
            StoredCoverFetcher(data, options, imageLoader)
    }
}
