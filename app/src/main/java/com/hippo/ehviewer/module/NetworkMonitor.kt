package com.hippo.ehviewer.module

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Observes network connectivity using [ConnectivityManager.NetworkCallback].
 * Provides a fast-path [isAvailable] check plus an observable [isAvailableFlow]
 * and a [awaitAvailable] suspend helper so the download worker can pause until
 * connectivity returns instead of failing.
 *
 * Tracks the set of currently-up INTERNET-capable [Network]s **by identity** rather
 * than a bare counter. This matters because the seed network — read once from
 * [ConnectivityManager.activeNetwork] before the callback is registered — is the same
 * network for which the system immediately re-delivers `onAvailable` on registration.
 * A counter would increment twice for that single network and never return to zero on
 * the first real disconnect, leaving [isAvailable] stuck `true` and defeating the
 * download "offline = pause" path. Adding the same [Network] to a set is idempotent,
 * so seed + registration callback count once.
 *
 * Initialized once via [NetworkModule] and kept alive for the process lifetime.
 */
class NetworkMonitor(context: Context) {

    private val lock = Any()

    /** Identities of currently-up INTERNET networks. Guarded by [lock]. */
    private val availableNetworks = HashSet<Network>()

    private val _isAvailableFlow = MutableStateFlow(false)

    /** Live availability; `true` once at least one INTERNET-capable network is up. */
    val isAvailableFlow: StateFlow<Boolean> = _isAvailableFlow.asStateFlow()

    val isAvailable: Boolean get() = synchronized(lock) { availableNetworks.isNotEmpty() }

    /** Suspends until the network is available; returns immediately if already up. */
    suspend fun awaitAvailable() {
        isAvailableFlow.first { it }
    }

    /**
     * Mark [network] available and publish. Extracted from the callback so the
     * set→flow mapping is unit-testable without driving a real [ConnectivityManager].
     * Idempotent per network identity, so the seed and the registration callback for
     * the same network only count once. Synchronized so the set mutation and the flow
     * publish stay consistent when callbacks arrive concurrently.
     */
    internal fun handleAvailable(network: Network) {
        synchronized(lock) {
            availableNetworks.add(network)
            _isAvailableFlow.value = availableNetworks.isNotEmpty()
        }
    }

    /** Mark [network] lost and publish. */
    internal fun handleLost(network: Network) {
        synchronized(lock) {
            availableNetworks.remove(network)
            _isAvailableFlow.value = availableNetworks.isNotEmpty()
        }
    }

    private val mCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handleAvailable(network)
        override fun onLost(network: Network) = handleLost(network)
    }

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Seed from the currently active network before callbacks fire, so a synchronous
        // isAvailable read right after construction reflects reality. The system re-delivers
        // onAvailable for this same network on registration; tracking by identity makes that
        // duplicate a no-op. One binder call on the main thread — acceptable.
        val active = cm.activeNetwork
        if (active != null &&
            cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        ) {
            handleAvailable(active)
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, mCallback)
    }
}
