package com.hippo.ehviewer

import android.content.Context
import com.hippo.ehviewer.module.AppModule
import com.hippo.ehviewer.module.ClientModule
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

    lateinit var networkModule: NetworkModule
        private set
    lateinit var clientModule: ClientModule
        private set
    lateinit var dataModule: DataModule
        private set
    lateinit var appModule: AppModule
        private set

    /**
     * Initialize all modules. Must be called from EhApplication.onCreate()
     * after Settings and EhDB have been initialized.
     */
    fun initialize(context: Context) {
        appModule = AppModule().also { it.initialize() }
        networkModule = NetworkModule(context)
        clientModule = ClientModule(context, networkModule)
        dataModule = DataModule(context)
    }
}
