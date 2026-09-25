package com.lanraragi.reader

import android.content.Context
import com.lanraragi.reader.module.IAppModule
import com.lanraragi.reader.module.INetworkModule
import com.lanraragi.reader.module.NetworkMonitor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

/**
 * [INetworkModule] for [ServiceRegistry.initializeForTest] whose three
 * clients are [client]. With a null [client] (or [cacheDir]) the matching
 * members throw, for tests that must not touch the network at all.
 *
 * [NetworkMonitor] always throws: retryOnFailure reads it through
 * runCatching{}.getOrDefault(false), so a throw is treated as "online",
 * which is what tests want.
 */
fun stubNetworkModule(client: OkHttpClient? = null, cacheDir: File? = null): INetworkModule =
    object : INetworkModule {
        override val cache: Cache get() = Cache(cacheDir ?: unsupported(), 1024)
        override val proxySelector: AppProxySelector get() = unsupported()
        override val okHttpClient: OkHttpClient get() = client ?: unsupported()
        override val longReadClient: OkHttpClient get() = client ?: unsupported()
        override val uploadClient: OkHttpClient get() = client ?: unsupported()
        override val networkMonitor: NetworkMonitor get() = unsupported()
    }

/** [IAppModule] that only supplies [context]; the global-stuff and temp caches are inert. */
fun stubAppModule(context: Context): IAppModule = object : IAppModule {
    override fun getContext(): Context = context
    override fun initialize() {}
    override fun putGlobalStuff(o: Any): Int = 0
    override fun containGlobalStuff(id: Int): Boolean = false
    override fun getGlobalStuff(id: Int): Any? = null
    override fun removeGlobalStuff(id: Int): Any? = null
    override fun removeGlobalStuff(o: Any) {}
    override fun putTempCache(key: String, o: Any): String = key
    override fun containTempCache(key: String): Boolean = false
    override fun getTempCache(key: String): Any? = null
    override fun removeTempCache(key: String): Any? = null
}

private fun unsupported(): Nothing = throw UnsupportedOperationException()
