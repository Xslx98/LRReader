package com.lanraragi.reader.module

import android.content.Context

/**
 * Abstraction over [AppModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution.
 */
interface IAppModule {

    /** Returns the application context held by this module. */
    fun getContext(): Context

    /**
     * One-time setup for module-managed persistent state (e.g. restoring id generator
     * seed from Settings). Called from [ServiceRegistry.initialize].
     */
    fun initialize()

    // --- Global Stuff Registry (ephemeral handoff) ---
    // Only ContentLayout's saved-rows handoff across instance-state save and
    // restore still uses it; new code passes state through scene arguments
    // or a ViewModel.

    /** Registers [o] (weakly held) and returns its id. */
    fun putGlobalStuff(o: Any): Int

    /** Removes and returns the object registered as [id], if still alive. */
    fun removeGlobalStuff(id: Int): Any?
}
