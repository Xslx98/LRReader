package com.lanraragi.reader.server

import com.lanraragi.reader.dao.MiscRoomDao
import com.lanraragi.reader.dao.ServerProfile

/**
 * Boot step for installs from before v2.5, which kept a copy of the active
 * server in the secure store. Room's `IS_ACTIVE` profile is the only record
 * now: when Room has none, the copy is handed to Room (the stored profile
 * id, or, for an install whose server never became a profile row, a new
 * row with its key), then the copy is deleted. Runs every boot; after the
 * copy is gone it is one secure-store read.
 */
internal object LegacyActiveSessionMigration {

    suspend fun run(dao: MiscRoomDao) {
        val legacy = SecureCredentialStore.legacyActiveSession() ?: return
        val profiles = dao.getAllServerProfiles()
        if (profiles.none { it.isActive }) {
            val stored = profiles.firstOrNull { it.id == legacy.profileId }
            when {
                stored != null -> dao.setActiveProfileExclusive(stored.id)
                profiles.isEmpty() && legacy.url != null -> {
                    val row = ServerProfile(0, legacy.name ?: "LANraragi", legacy.url, true, legacy.allowCleartext)
                    dao.insertActive(row) { id -> ServerSession.setApiKeyForProfile(id, legacy.apiKey) }
                }
            }
        }
        SecureCredentialStore.dropLegacyActiveSession()
    }
}
