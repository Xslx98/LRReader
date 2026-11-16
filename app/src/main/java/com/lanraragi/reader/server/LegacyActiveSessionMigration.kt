package com.lanraragi.reader.server

import com.lanraragi.reader.dao.MiscRoomDao
import com.lanraragi.reader.dao.ServerProfile

/**
 * Boot step for installs from before v2.5, which kept a copy of the active
 * server in the secure store. Room's `IS_ACTIVE` profile is the only record
 * now: when Room has none, the copy is handed to Room (the stored profile
 * id, else the profile with the stored URL, or, for an install whose server
 * never became a profile row, a new row), with the stored key for a profile
 * that has none of its own. Then the copy is deleted. Runs every boot;
 * after the copy is gone it is one plain-prefs read.
 */
internal object LegacyActiveSessionMigration {

    suspend fun run(dao: MiscRoomDao) {
        if (!SecureCredentialStore.mayHoldLegacyActiveSession()) return
        val legacy = SecureCredentialStore.legacyActiveSession()
        if (legacy == null) {
            SecureCredentialStore.dropLegacyActiveSession()
            return
        }
        val profiles = dao.getAllServerProfiles()
        if (profiles.none { it.isActive }) {
            val stored = profiles.firstOrNull { it.id == legacy.profileId }
                ?: legacy.url?.let { url -> profiles.firstOrNull { it.url.removeSuffix("/") == url.removeSuffix("/") } }
            when {
                stored != null -> {
                    if (legacy.apiKey != null && ServerSession.getApiKeyForProfile(stored.id) == null) {
                        ServerSession.setApiKeyForProfile(stored.id, legacy.apiKey)
                    }
                    dao.setActiveProfileExclusive(stored.id)
                }
                profiles.isEmpty() && legacy.url != null -> {
                    val row = ServerProfile(0, legacy.name ?: "LANraragi", legacy.url, true, legacy.allowCleartext)
                    dao.insertActive(row) { id -> ServerSession.setApiKeyForProfile(id, legacy.apiKey) }
                }
            }
        }
        SecureCredentialStore.dropLegacyActiveSession()
    }
}
