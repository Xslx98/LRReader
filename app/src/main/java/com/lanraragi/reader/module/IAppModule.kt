package com.lanraragi.reader.module

import android.content.Context

/**
 * Abstraction over [AppModule] to allow ServiceRegistry consumers to depend on the
 * contract rather than the concrete implementation. Enables test-time substitution.
 */
interface IAppModule {

    /** Returns the application context held by this module. */
    fun getContext(): Context
}
