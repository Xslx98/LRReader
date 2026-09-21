package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Remembers the cover page the user chose for a tankoubon (spec
 * 2026-09-22-tank-cover §4) so the app can put it back after its own
 * reorders: upstream queues a regeneration job (first member, page 1) after
 * every `PUT /api/tankoubons/{id}` that changes `archives` and after a
 * first-member removal, and has no custom-cover flag to skip it.
 *
 * Small JSON map in SharedPreferences (`tank_cover_choice`), no Room change.
 * The choice is only consulted on the app's OWN writes; server-side changes
 * made elsewhere are never intercepted.
 */
class TankCoverChoiceStore(private val storage: Storage = SettingsStorage) {

    /** Plain string slot; production = [Settings], tests = a map. */
    interface Storage {
        fun read(): String?
        fun write(value: String?)
    }

    @Serializable
    data class Choice(val arcid: String, val page0: Int, val profileId: Long)

    fun get(tankId: String): Choice? = load()[tankId]

    fun put(tankId: String, choice: Choice) {
        val map = load().toMutableMap()
        map[tankId] = choice
        save(map)
    }

    fun remove(tankId: String) {
        val map = load()
        if (tankId !in map) return
        save(map - tankId)
    }

    /**
     * Membership changed: a choice whose archive is no longer a member is
     * dropped (the server will regenerate from the new first member).
     * @return the still-valid choice, or null
     */
    fun reconcile(tankId: String, memberIds: Collection<String>): Choice? {
        val choice = get(tankId) ?: return null
        if (choice.arcid in memberIds) return choice
        remove(tankId)
        return null
    }

    private fun load(): Map<String, Choice> {
        val raw = storage.read()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Choice>>(raw) }.getOrDefault(emptyMap())
    }

    private fun save(map: Map<String, Choice>) {
        storage.write(if (map.isEmpty()) null else json.encodeToString(map))
    }

    private object SettingsStorage : Storage {
        override fun read(): String? = Settings.getString(KEY, null)
        override fun write(value: String?) = Settings.putString(KEY, value)
    }

    companion object {
        const val KEY = "tank_cover_choice"
        private val json = Json { ignoreUnknownKeys = true }

        /** Process-wide instance over [Settings]. */
        val default: TankCoverChoiceStore by lazy { TankCoverChoiceStore() }
    }
}
