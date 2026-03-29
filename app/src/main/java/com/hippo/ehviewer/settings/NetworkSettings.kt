package com.hippo.ehviewer.settings

import com.hippo.ehviewer.EhProxySelector
import com.hippo.ehviewer.Settings

/**
 * Network-related settings extracted from Settings.java.
 * Covers built-in hosts, DNS-over-HTTPS, domain fronting, proxy configuration.
 */
object NetworkSettings {

    // --- Built-in Hosts ---
    const val KEY_BUILT_IN_HOSTS = "built_in_hosts"

    @JvmStatic
    fun getBuiltInHosts(): Boolean = Settings.getBoolean(KEY_BUILT_IN_HOSTS, true)

    @JvmStatic
    fun putBuiltInHosts(value: Boolean) = Settings.putBoolean(KEY_BUILT_IN_HOSTS, value)

    // --- Built-in EX Hosts ---
    const val KEY_BUILT_EX_HOSTS = "built_ex_hosts"

    @JvmStatic
    fun getBuiltEXHosts(): Boolean = Settings.getBoolean(KEY_BUILT_EX_HOSTS, true)

    @JvmStatic
    fun putBuiltEXHosts(value: Boolean) = Settings.putBoolean(KEY_BUILT_EX_HOSTS, value)

    // --- DNS-over-HTTPS ---
    const val KEY_DOH = "dns_over_https"

    @JvmStatic
    fun getDoH(): Boolean = Settings.getBoolean(KEY_DOH, false)

    @JvmStatic
    fun putDoH(value: Boolean) = Settings.putBoolean(KEY_DOH, value)

    // --- Domain Fronting ---
    const val KEY_DOMAIN_FRONTING = "domain_fronting"

    @JvmStatic
    fun getDF(): Boolean = Settings.getBoolean(KEY_DOMAIN_FRONTING, true)

    @JvmStatic
    fun putDF(value: Boolean) = Settings.putBoolean(KEY_DOMAIN_FRONTING, value)

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
