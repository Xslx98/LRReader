package com.lanraragi.reader.module

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit C35: only the API client keeps the shared HTTP cache; every client
 * moving big, single-use bodies (pages, extraction, uploads, APK / tag DB /
 * stats dumps, thumbnails already cached by Conaco) bypasses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NetworkModuleCacheTest {

    private val module = ApplicationProvider.getApplicationContext<Application>().let { ctx ->
        com.lanraragi.reader.Settings.initialize(ctx)
        NetworkModule(ctx)
    }

    @Test
    fun api_client_keeps_the_http_cache() {
        assertNotNull(module.okHttpClient.cache)
    }

    @Test
    fun big_body_clients_bypass_the_http_cache() {
        assertNull("longReadClient", module.longReadClient.cache)
        assertNull("uploadClient", module.uploadClient.cache)
        assertNull("largeFileClient", module.largeFileClient.cache)
        assertNull("pageStreamClient", module.pageStreamClient.cache)
        assertNull("thumbFetchClient", module.thumbFetchClient.cache)
    }
}
