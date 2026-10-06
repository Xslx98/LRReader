package com.lanraragi.reader.client.api

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * SharedPreferences-backed [ServerCapabilityCache.Store]. One key per server,
 * value = comma-joined namespaces (LANraragi itself configures the list as a
 * comma-separated string, so a namespace can never contain a comma). An empty
 * string is a valid "observed, nothing excluded" record, distinct from a
 * missing key.
 */
class PrefsServerCapabilityStore(private val prefs: SharedPreferences) : ServerCapabilityCache.Store {

    override fun loadExcludedNamespaces(baseUrl: String): Set<String>? {
        val raw = prefs.getString(key(baseUrl), null) ?: return null
        return raw.split(',').filterTo(LinkedHashSet()) { it.isNotEmpty() }
    }

    override fun saveExcludedNamespaces(baseUrl: String, namespaces: Set<String>) {
        prefs.edit { putString(key(baseUrl), namespaces.joinToString(",")) }
    }

    override fun forget(baseUrl: String) {
        prefs.edit { remove(key(baseUrl)) }
    }

    private fun key(baseUrl: String) = KEY_PREFIX + baseUrl

    companion object {
        private const val KEY_PREFIX = "server_excluded_namespaces:"
    }
}
