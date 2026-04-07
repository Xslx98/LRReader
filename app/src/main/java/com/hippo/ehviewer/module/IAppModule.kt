package com.hippo.ehviewer.module

import android.content.Context
import java.util.concurrent.ExecutorService

/**
 * Abstraction over [AppModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution.
 */
interface IAppModule {

    /** Returns the application context held by this module. */
    fun getContext(): Context

    /** Shared [ExecutorService] for miscellaneous fire-and-forget parallel work. */
    val executorService: ExecutorService

    /**
     * One-time setup for module-managed persistent state (e.g. restoring id generator
     * seed from Settings). Called from [ServiceRegistry.initialize].
     */
    fun initialize()

    // --- Global Stuff Registry (ephemeral cross-scene handoff) ---

    fun putGlobalStuff(o: Any): Int

    fun containGlobalStuff(id: Int): Boolean

    fun getGlobalStuff(id: Int): Any?

    fun removeGlobalStuff(id: Int): Any?

    fun removeGlobalStuff(o: Any)

    // --- Temp Cache (ephemeral keyed storage) ---

    fun putTempCache(key: String, o: Any): String

    fun containTempCache(key: String): Boolean

    fun getTempCache(key: String): Any?

    fun removeTempCache(key: String): Any?
}
