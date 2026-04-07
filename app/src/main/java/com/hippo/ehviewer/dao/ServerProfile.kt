package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing a LANraragi server profile.
 * Stores connection settings (URL) and an active flag
 * to identify the currently connected server.
 * API keys are stored in EncryptedSharedPreferences via LRRAuthManager.
 *
 * `allowCleartext` is the per-profile opt-in for plain HTTP. The
 * authoritative cleartext gate (LRRCleartextRejectionInterceptor) reads
 * the active profile's flag (cached in LRRAuthManager) and rejects HTTP
 * requests when the flag is false. Defaults to true for backwards
 * compatibility — existing profiles are grandfathered by MIGRATION_13_14.
 */
@Entity(tableName = "SERVER_PROFILES")
data class ServerProfile(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ID")
    val id: Long = 0,

    @ColumnInfo(name = "NAME")
    val name: String,

    @ColumnInfo(name = "URL")
    val url: String,

    @ColumnInfo(name = "IS_ACTIVE")
    val isActive: Boolean = false,

    @ColumnInfo(name = "ALLOW_CLEARTEXT", defaultValue = "1")
    val allowCleartext: Boolean = true
)
