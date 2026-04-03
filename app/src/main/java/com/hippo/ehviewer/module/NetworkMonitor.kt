package com.hippo.ehviewer.module

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Observes network connectivity using [ConnectivityManager.NetworkCallback].
 * Provides a fast-path [isAvailable] check so API calls can fail immediately
 * instead of waiting for a connect timeout when the device is offline.
 *
 * Initialized once via [NetworkModule] and kept alive for the process lifetime.
 */
class NetworkMonitor(context: Context) {

    @Volatile
    private var mIsAvailable: Boolean = false

    val isAvailable: Boolean get() = mIsAvailable

    private val mCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mIsAvailable = true
        }

        override fun onLost(network: Network) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Only mark offline if there is genuinely no remaining network
            mIsAvailable = cm.activeNetwork != null
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            mIsAvailable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Seed the initial state before the callback fires
        val active = cm.activeNetwork
        mIsAvailable = if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            false
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, mCallback)
    }
}
