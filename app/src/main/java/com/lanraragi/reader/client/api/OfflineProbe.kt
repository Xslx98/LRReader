package com.lanraragi.reader.client.api

/**
 * Whether the device is known to be offline, so [retryOnFailure] fails fast
 * with [LRROfflineException] instead of waiting for the connect timeout.
 *
 * The transport layer cannot reach the service locator (architecture rule
 * D3), so ServiceRegistry installs the real check when it is first used. Until
 * then, and whenever the check throws (no network module installed yet), the
 * device counts as online. This is an injection point, like the framework's,
 * not a place for state of its own.
 */
object OfflineProbe {

    @Volatile
    private var probe: () -> Boolean = { false }

    fun install(probe: () -> Boolean) {
        this.probe = probe
    }

    fun isKnownOffline(): Boolean = runCatching(probe).getOrDefault(false)
}
