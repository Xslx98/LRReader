package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The tanks most recently added to from the picker sheet, newest first, per
 * server profile (docs/plan-2.4.0-tank-ux.md). Drives the sheet's "Recent"
 * section. Small JSON map in SharedPreferences (`tank_recent`), no Room
 * change; ids the server no longer lists are simply skipped by the reader.
 */
class TankRecentStore(private val storage: Storage = SettingsStorage) {

    /** Plain string slot; production = [Settings], tests = a map. */
    interface Storage {
        fun read(): String?
        fun write(value: String?)
    }

    fun get(profileId: Long): List<String> = load()[profileId.toString()].orEmpty()

    fun touch(profileId: Long, tankId: String) {
        val map = load().toMutableMap()
        val key = profileId.toString()
        map[key] = (listOf(tankId) + map[key].orEmpty().filter { it != tankId }).take(LIMIT)
        save(map)
    }

    fun remove(profileId: Long, tankId: String) {
        val map = load()
        val key = profileId.toString()
        val ids = map[key] ?: return
        if (tankId !in ids) return
        save(map + (key to ids - tankId))
    }

    /** Drops [profileId]'s list (the profile was deleted). */
    fun removeProfile(profileId: Long) {
        val map = load()
        val key = profileId.toString()
        if (key in map) save(map - key)
    }

    private fun load(): Map<String, List<String>> {
        val raw = storage.read()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, List<String>>>(raw)
        } catch (ignored: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: a corrupt slot reads as empty.
            emptyMap()
        }
    }

    private fun save(map: Map<String, List<String>>) {
        val kept = map.filterValues { it.isNotEmpty() }
        storage.write(if (kept.isEmpty()) null else json.encodeToString(kept))
    }

    private object SettingsStorage : Storage {
        override fun read(): String? = Settings.getString(KEY, null)
        override fun write(value: String?) = Settings.putString(KEY, value)
    }

    companion object {
        const val KEY = "tank_recent"
        const val LIMIT = 5
        private val json = Json { ignoreUnknownKeys = true }

        /** Process-wide instance over [Settings]. */
        val default: TankRecentStore by lazy { TankRecentStore() }
    }
}
