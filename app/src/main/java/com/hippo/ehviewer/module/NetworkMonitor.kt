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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Observes network connectivity using [ConnectivityManager.NetworkCallback].
 * Provides a fast-path [isAvailable] check plus an observable [isAvailableFlow]
 * and a [awaitAvailable] suspend helper so the download worker can pause until
 * connectivity returns instead of failing.
 *
 * Uses an [AtomicInteger] counter rather than calling [ConnectivityManager.activeNetwork]
 * inside callbacks to avoid blocking binder calls on the system callback thread.
 *
 * Initialized once via [NetworkModule] and kept alive for the process lifetime.
 */
class NetworkMonitor(context: Context) {

    private val mNetworkCount = AtomicInteger(0)

    private val _isAvailableFlow = MutableStateFlow(false)

    private val lock = Any()

    /** Live availability; `true` once at least one INTERNET-capable network is up. */
    val isAvailableFlow: StateFlow<Boolean> = _isAvailableFlow.asStateFlow()

    val isAvailable: Boolean get() = mNetworkCount.get() > 0

    /** Suspends until the network is available; returns immediately if already up. */
    suspend fun awaitAvailable() {
        isAvailableFlow.first { it }
    }

    /**
     * Increment the live-network count and publish availability. Extracted from the
     * callback so the count→flow mapping is unit-testable without driving a real
     * [ConnectivityManager]. Synchronized so the count mutation and the flow publish
     * stay consistent if callbacks ever arrive concurrently.
     */
    internal fun handleAvailable() {
        synchronized(lock) {
            mNetworkCount.incrementAndGet()
            _isAvailableFlow.value = true
        }
    }

    /** Decrement (floored at 0) and publish availability. */
    internal fun handleLost() {
        synchronized(lock) {
            val n = mNetworkCount.updateAndGet { if (it > 0) it - 1 else 0 }
            _isAvailableFlow.value = n > 0
        }
    }

    private val mCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handleAvailable()
        override fun onLost(network: Network) = handleLost()
    }

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Seed the initial count from the currently active network before callbacks fire.
        // Called once on the main thread — a binder call is acceptable here.
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                mNetworkCount.set(1)
            }
        }
        _isAvailableFlow.value = mNetworkCount.get() > 0

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, mCallback)
    }
}
