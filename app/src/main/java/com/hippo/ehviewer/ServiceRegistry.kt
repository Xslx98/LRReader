package com.hippo.ehviewer

import android.content.Context
import com.hippo.ehviewer.client.lrr.LRRTagCache
import com.hippo.ehviewer.module.AppModule
import com.hippo.ehviewer.module.ClientModule
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.ehviewer.module.DataModule
import com.hippo.ehviewer.module.NetworkModule

/**
 * Central service registry that replaces the Service Locator pattern
 * previously embedded in EhApplication. All singletons are organized
 * into domain-specific modules.
 *
 * Must be initialized after Settings.initialize() in EhApplication.onCreate().
 */
object ServiceRegistry {

    private var _networkModule: NetworkModule? = null
    private var _clientModule: ClientModule? = null
    private var _dataModule: DataModule? = null
    private var _appModule: AppModule? = null
    private var _coroutineModule: CoroutineModule? = null

    val networkModule: NetworkModule
        get() = _networkModule
            ?: error("ServiceRegistry.networkModule accessed before initialize()")
    val clientModule: ClientModule
        get() = _clientModule
            ?: error("ServiceRegistry.clientModule accessed before initialize()")
    val dataModule: DataModule
        get() = _dataModule
            ?: error("ServiceRegistry.dataModule accessed before initialize()")
    val appModule: AppModule
        get() = _appModule
            ?: error("ServiceRegistry.appModule accessed before initialize()")
    val coroutineModule: CoroutineModule
        get() = _coroutineModule
            ?: error("ServiceRegistry.coroutineModule accessed before initialize()")

    /**
     * Initialize all modules. Must be called from EhApplication.onCreate()
     * after Settings and EhDB have been initialized.
     */
    fun initialize(context: Context) {
        _appModule = AppModule(context).also { it.initialize() }
        _coroutineModule = CoroutineModule()
        _networkModule = NetworkModule(context)
        _clientModule = ClientModule(context, networkModule)
        _dataModule = DataModule(context)
    }

    /**
     * Initialize only the coroutine module — for tests that don't need
     * full ServiceRegistry but need CoroutineBridge to work.
     */
    @androidx.annotation.VisibleForTesting
    fun initializeForTest(coroutine: CoroutineModule) {
        _coroutineModule = coroutine
    }

    /**
     * Clear all in-memory and disk caches. Call when switching server profiles
     * so that content from the previous server does not appear in the new one.
     */
    fun clearAllCaches() {
        try { _networkModule?.cache?.evictAll() } catch (_: Exception) {}
        _dataModule?.galleryDetailCache?.evictAll()
        try { _dataModule?.spiderInfoCache?.clear() } catch (_: Exception) {}
        LRRTagCache.clear()
    }
}
