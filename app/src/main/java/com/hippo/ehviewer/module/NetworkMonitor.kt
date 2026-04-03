package com.hippo.ehviewer.module

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Observes network connectivity using [ConnectivityManager.NetworkCallback].
 * Provides a fast-path [isAvailable] check so API calls can fail immediately
 * instead of waiting for a connect timeout when the device is offline.
 *
 * Uses an [AtomicInteger] counter rather than calling [ConnectivityManager.activeNetwork]
 * inside callbacks to avoid blocking binder calls on the system callback thread.
 *
 * Initialized once via [NetworkModule] and kept alive for the process lifetime.
 */
class NetworkMonitor(context: Context) {

    private val mNetworkCount = AtomicInteger(0)

    val isAvailable: Boolean get() = mNetworkCount.get() > 0

    private val mCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mNetworkCount.incrementAndGet()
        }

        override fun onLost(network: Network) {
            // Floor at 0 to guard against any callback ordering edge cases.
            mNetworkCount.updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Seed the initial count from the currently active network before callbacks fire.
        // This is called once on the app's main thread — not a callback — so a binder
        // call is acceptable here.
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                mNetworkCount.set(1)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, mCallback)
    }
}
