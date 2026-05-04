package com.hippo.ehviewer.settings

import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Settings

/**
 * Network-related settings extracted from Settings.java.
 * Covers proxy configuration. (DNS-over-HTTPS scaffolding was removed
 * as never-wired dead code; NetworkModule resolves via Dns.SYSTEM.)
 */
object NetworkSettings {

    // --- Proxy Type ---
    private const val KEY_PROXY_TYPE = "proxy_type"
    private val DEFAULT_PROXY_TYPE = EhProxySelector.TYPE_SYSTEM

    @JvmStatic
    fun getProxyType(): Int = Settings.getInt(KEY_PROXY_TYPE, DEFAULT_PROXY_TYPE)

    @JvmStatic
    fun putProxyType(value: Int) = Settings.putInt(KEY_PROXY_TYPE, value)

    // --- Proxy IP ---
    private const val KEY_PROXY_IP = "proxy_ip"

    @JvmStatic
    fun getProxyIp(): String? = Settings.getString(KEY_PROXY_IP, null)

    @JvmStatic
    fun putProxyIp(value: String?) = Settings.putString(KEY_PROXY_IP, value)

    // --- Proxy Port ---
    private const val KEY_PROXY_PORT = "proxy_port"

    @JvmStatic
    fun getProxyPort(): Int = Settings.getInt(KEY_PROXY_PORT, -1)

    @JvmStatic
    fun putProxyPort(value: Int) = Settings.putInt(KEY_PROXY_PORT, value)

    // --- Cellular Network Warning ---
    private const val KEY_CELLULAR_NETWORK_WARNING = "cellular_network_warning"

    @JvmStatic
    fun getCellularNetworkWarning(): Boolean = Settings.getBoolean(KEY_CELLULAR_NETWORK_WARNING, false)
}
