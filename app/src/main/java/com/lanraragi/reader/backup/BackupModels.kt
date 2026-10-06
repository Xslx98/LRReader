package com.lanraragi.reader.backup

import kotlinx.serialization.Serializable

/**
 * The backup file (audit 2026-10-04 C05, ruling R18). Plain DTOs: Room entities
 * stay free of serialization concerns, and the file format can outlive schema
 * changes. API keys and the lock pattern are never part of it.
 * Design: docs/superpowers/specs/2026-10-06-backup-restore-design.md (notes repo).
 */
@Serializable
data class LrrBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: Long = 0,
    val appVersion: String = "",
    val profiles: List<BackupProfile> = emptyList(),
    val archives: List<BackupArchive> = emptyList(),
    val downloadLabels: List<BackupLabel> = emptyList(),
    val tankGroups: List<BackupTankGroup> = emptyList(),
    val quickSearches: List<BackupQuickSearch> = emptyList(),
    val searchHistory: List<BackupSearchHistory> = emptyList(),
    val dailyAggregates: List<BackupDailyAggregate> = emptyList(),
    val readingProgress: List<BackupReadingProgress> = emptyList(),
    val settings: List<BackupSetting> = emptyList(),
) {
    companion object {
        const val FORMAT = "lrreader-backup"
        const val VERSION = 1
    }
}

@Serializable
data class BackupProfile(
    val id: Long,
    val name: String,
    val url: String,
    val isActive: Boolean = false,
    val allowCleartext: Boolean = true,
)

@Serializable
data class BackupArchive(
    val arcid: String,
    val profileId: Long,
    val archiveJson: String,
    /** [com.lanraragi.reader.download.DownloadState] code, null without a download. */
    val downloadState: Int? = null,
    val downloadLegacy: Int = 0,
    val downloadTime: Long? = null,
    val downloadLabel: String? = null,
    val downloadArchiveUri: String? = null,
    val downloadRootUri: String? = null,
    val downloadTankId: String? = null,
    val historyTime: Long? = null,
    val historyMode: Int = 0,
    val historyScrollFraction: Float? = null,
    val favoriteTime: Long? = null,
)

@Serializable
data class BackupLabel(val label: String, val time: Long = 0)

@Serializable
data class BackupTankGroup(
    val tankId: String,
    val profileId: Long,
    val name: String,
    val memberIdsJson: String,
    val createdTime: Long = 0,
)

@Serializable
data class BackupQuickSearch(
    val name: String? = null,
    val mode: Int = 0,
    val category: Int = 0,
    val keyword: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val advanceSearch: Int = 0,
    val minRating: Int = 0,
    val pageFrom: Int = 0,
    val pageTo: Int = 0,
    val time: Long = 0,
)

@Serializable
data class BackupSearchHistory(val query: String, val profileId: Long, val lastUsed: Long)

@Serializable
data class BackupDailyAggregate(val epochDay: Long, val profileId: Long, val pagesRead: Long, val completed: Int)

/** One `reading_progress` entry: 0-indexed page saved at [savedAt] (epoch seconds, 0 = unknown). */
@Serializable
data class BackupReadingProgress(val arcid: String, val page: Int, val savedAt: Long = 0)

/** A whitelisted default-prefs value; [type] is one of [BackupSettings.TYPES]. */
@Serializable
data class BackupSetting(val key: String, val type: String, val value: String)
