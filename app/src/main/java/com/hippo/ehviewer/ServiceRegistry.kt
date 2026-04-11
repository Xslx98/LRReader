package com.hippo.ehviewer

import android.content.Context
import com.hippo.ehviewer.module.AppModule
import com.hippo.ehviewer.module.Cacheable
import com.hippo.ehviewer.module.ClientModule
import com.hippo.ehviewer.module.CoroutineModule
import com.hippo.ehviewer.module.DataModule
import com.hippo.ehviewer.module.IAppModule
import com.hippo.ehviewer.module.IClientModule
import com.hippo.ehviewer.module.ICoroutineModule
import com.hippo.ehviewer.module.IDataModule
import com.hippo.ehviewer.module.INetworkModule
import com.hippo.ehviewer.module.NetworkModule

/**
 * Central service registry that replaces the Service Locator pattern
 * previously embedded in EhApplication. All singletons are organized
 * into domain-specific modules and exposed as interface references so
 * that consumers depend on contracts rather than concrete classes.
 *
 * Must be initialized after Settings.initialize() in EhApplication.onCreate().
 */
object ServiceRegistry {

    private var _networkModule: INetworkModule? = null
    private var _clientModule: IClientModule? = null
    private var _dataModule: IDataModule? = null
    private var _appModule: IAppModule? = null
    private var _coroutineModule: ICoroutineModule? = null

    private val cacheables = mutableListOf<Cacheable>()

    val networkModule: INetworkModule
        get() = _networkModule
            ?: error("ServiceRegistry.networkModule accessed before initialize()")
    val clientModule: IClientModule
        get() = _clientModule
            ?: error("ServiceRegistry.clientModule accessed before initialize()")
    val dataModule: IDataModule
        get() = _dataModule
            ?: error("ServiceRegistry.dataModule accessed before initialize()")
    val appModule: IAppModule
        get() = _appModule
            ?: error("ServiceRegistry.appModule accessed before initialize()")
    val coroutineModule: ICoroutineModule
        get() = _coroutineModule
            ?: error("ServiceRegistry.coroutineModule accessed before initialize()")

    /**
     * Initialize all modules with their production implementations. Must be called
     * from EhApplication.onCreate() after Settings and EhDB have been initialized.
     */
    fun initialize(context: Context) {
        cacheables.clear()
        _appModule = AppModule(context).also { it.initialize() }
        _coroutineModule = CoroutineModule()
        val network = NetworkModule(context)
        _networkModule = network
        registerCacheable(network)
        // ClientModule.init registers LRRTagCache as a Cacheable
        _clientModule = ClientModule(context, network)
        val data = DataModule(context)
        _dataModule = data
        registerCacheable(data)
    }

    /**
     * Install test doubles for any subset of modules. Any module left `null` keeps
     * whatever was previously installed (typically nothing in a fresh test), allowing
     * callers to layer mocks on top of a previous [initializeForTest] call.
     *
     * Use [com.hippo.ehviewer.TestServiceRegistryHelper] for the common case of
     * wiring an in-memory database + a `MockWebServer`-backed OkHttp client.
     */
    @androidx.annotation.VisibleForTesting
    fun initializeForTest(
        coroutine: ICoroutineModule? = CoroutineModule(),
        network: INetworkModule? = null,
        client: IClientModule? = null,
        data: IDataModule? = null,
        app: IAppModule? = null,
    ) {
        cacheables.clear()
        if (coroutine != null) _coroutineModule = coroutine
        if (network != null) _networkModule = network
        if (client != null) _clientModule = client
        if (data != null) _dataModule = data
        if (app != null) _appModule = app
    }

    /**
     * Register a [Cacheable] whose [Cacheable.clearCache] will be called
     * by [clearAllCaches]. Modules call this during their initialization
     * to register caches they own, following the open/closed principle.
     */
    fun registerCacheable(cacheable: Cacheable) {
        cacheables.add(cacheable)
    }

    /**
     * Clear all in-memory and disk caches. Call when switching server profiles
     * so that content from the previous server does not appear in the new one.
     *
     * Iterates all [Cacheable] instances registered via [registerCacheable].
     */
    fun clearAllCaches() {
        for (cacheable in cacheables) {
            cacheable.clearCache()
        }
    }
}
