package com.lanraragi.framework.lib.image

/**
 * Where the decoder reports failures. The framework does not know the app's
 * diagnostics, so the application installs a [Sink] at startup; until then
 * (and in unit tests) reports are dropped. Only failure paths call it, never
 * the per-frame render path.
 */
object ImageTelemetry {

    interface Sink {
        fun recordException(e: Throwable)
        fun logError(tag: String, message: String, e: Throwable)
    }

    @JvmField
    @Volatile
    var sink: Sink? = null

    fun recordException(e: Throwable) {
        sink?.recordException(e)
    }

    fun logError(tag: String, message: String, e: Throwable) {
        sink?.logError(tag, message, e)
    }
}
