package com.lanraragi.reader.backup

import com.lanraragi.reader.BuildConfig
import com.lanraragi.reader.dao.AppDatabase
import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The file is not a backup this version can read. */
class BackupFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Reads and writes [LrrBackup] files. */
object BackupCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun write(backup: LrrBackup, out: OutputStream) {
        out.write(json.encodeToString(LrrBackup.serializer(), backup).encodeToByteArray())
        out.flush()
    }

    /** @throws BackupFormatException for another file type or a backup from a newer app */
    fun read(input: InputStream): LrrBackup {
        val backup = decode(input.readBytes().decodeToString())
        val problem = when {
            backup.format != LrrBackup.FORMAT -> "Unknown format ${backup.format}"
            backup.version > LrrBackup.VERSION -> "Backup version ${backup.version} is newer"
            else -> null
        }
        if (problem != null) throw BackupFormatException(problem)
        return backup
    }

    private fun decode(text: String): LrrBackup = try {
        json.decodeFromString(LrrBackup.serializer(), text)
    } catch (e: SerializationException) {
        throw BackupFormatException("Not a backup file", e)
    } catch (e: IllegalArgumentException) {
        throw BackupFormatException("Not a backup file", e)
    }
}

/**
 * Collects everything a backup holds (audit C05, ruling R18). Reads run in one
 * transaction, so the file is a consistent snapshot.
 */
class BackupExporter(
    private val db: AppDatabase,
    private val settings: SharedPreferences,
    private val readingProgress: SharedPreferences,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    suspend fun collect(): LrrBackup = db.withTransaction {
        LrrBackup(
            createdAt = clockSeconds(),
            appVersion = BuildConfig.VERSION_NAME,
            profiles = db.miscDao().getAllServerProfiles().map {
                BackupProfile(it.id, it.name, it.url, it.isActive, it.allowCleartext)
            },
            archives = db.archiveLocalStateDao().getAllRows().map {
                BackupArchive(
                    arcid = it.arcid, profileId = it.serverProfileId, archiveJson = it.archiveJson,
                    downloadState = it.downloadState?.code, downloadLegacy = it.downloadLegacy,
                    downloadTime = it.downloadTime, downloadLabel = it.downloadLabel,
                    downloadArchiveUri = it.downloadArchiveUri, downloadRootUri = it.downloadRootUri,
                    downloadTankId = it.downloadTankId, historyTime = it.historyTime, historyMode = it.historyMode,
                    historyScrollFraction = it.historyScrollFraction, favoriteTime = it.favoriteTime,
                )
            },
            downloadLabels = db.downloadDao().getAllDownloadLabels().mapNotNull { l ->
                l.label?.let { BackupLabel(it, l.time) }
            },
            tankGroups = db.tankDownloadGroupDao().getAll().map {
                BackupTankGroup(it.tankId, it.serverProfileId, it.name, it.memberIdsJson, it.createdTime)
            },
            quickSearches = db.browsingDao().getAllQuickSearch().map {
                BackupQuickSearch(
                    it.name, it.mode, it.category, it.keyword, it.categoryId, it.categoryName,
                    it.advanceSearch, it.minRating, it.pageFrom, it.pageTo, it.time,
                )
            },
            searchHistory = db.browsingDao().getAllSearchHistory().map {
                BackupSearchHistory(it.query, it.serverProfileId, it.lastUsed)
            },
            dailyAggregates = db.statsDao().getAllDailyAggregates().map {
                BackupDailyAggregate(it.epochDay, it.serverProfileId, it.pagesRead, it.completed)
            },
            readingProgress = exportReadingProgress(readingProgress),
            settings = BackupSettings.export(settings),
        )
    }

    companion object {
        const val TS_SUFFIX = "_ts"
        private const val SEP = ":"

        /**
         * `reading_progress` holds `<profileId>:<arcid>` → page and `…_ts` → saved-at seconds
         * ([com.lanraragi.reader.gallery.LocalReadingProgress]); a not yet migrated bare arcid
         * key is exported without a profile.
         */
        fun exportReadingProgress(prefs: SharedPreferences): List<BackupReadingProgress> {
            val all = prefs.all
            return all.entries
                .filter { !it.key.endsWith(TS_SUFFIX) && it.value is Int }
                .map { (key, page) ->
                    val savedAt = (all[key + TS_SUFFIX] as? Long) ?: 0L
                    val profile = key.substringBefore(SEP, "").toLongOrNull()
                    val arcid = if (profile != null) key.substringAfter(SEP) else key
                    BackupReadingProgress(arcid, page as Int, savedAt, profile)
                }
                .sortedWith(compareBy({ it.profileId }, { it.arcid }))
        }
    }
}
