package com.hippo.ehviewer.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing a LANraragi server profile.
 * Stores connection settings (URL, API key) and an active flag
 * to identify the currently connected server.
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

    @ColumnInfo(name = "API_KEY")
    val apiKey: String? = null,

    @ColumnInfo(name = "IS_ACTIVE")
    val isActive: Boolean = false
)
