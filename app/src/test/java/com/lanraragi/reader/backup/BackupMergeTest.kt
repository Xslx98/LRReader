package com.lanraragi.reader.backup

import com.lanraragi.reader.backup.BackupMerge.Relink
import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restore merge rules (audit C05, ruling R18): merge, never lose local data. */
class BackupMergeTest {

    private val relink = Relink(rootUri = "file:///dl", dirname = "Title")

    private fun backup(
        historyTime: Long? = null,
        favoriteTime: Long? = null,
        downloadState: Int? = null,
        json: String = """{"b":1}""",
    ) = BackupArchive(
        arcid = "a", profileId = 7, archiveJson = json, downloadState = downloadState,
        downloadRootUri = "file:///old", historyTime = historyTime, historyMode = 2,
        historyScrollFraction = 0.5f, favoriteTime = favoriteTime,
    )

    @Test
    fun profiles_match_by_url_ignoring_a_trailing_slash() {
        val local = listOf(ServerProfile(id = 3, name = "Home", url = "http://10.0.0.2:3000"))
        val map = BackupMerge.matchProfiles(
            local,
            listOf(BackupProfile(1, "Home", "http://10.0.0.2:3000/"), BackupProfile(2, "Other", "https://x.example")),
        )
        assertEquals(mapOf(1L to 3L), map)
    }

    @Test
    fun a_new_archive_comes_from_the_backup() {
        val merged = BackupMerge.mergeArchive(null, backup(historyTime = 10, favoriteTime = 5), null)!!
        assertEquals(10L, merged.historyTime)
        assertEquals(5L, merged.favoriteTime)
        assertEquals("""{"b":1}""", merged.archiveJson)
        assertNull(merged.downloadState)
    }

    @Test
    fun the_later_history_wins_with_its_snapshot() {
        val local = ArchiveLocalState(arcid = "a", serverProfileId = 7, archiveJson = """{"l":1}""", historyTime = 20)
        val older = BackupMerge.mergeArchive(local, backup(historyTime = 10), null)!!
        assertEquals(20L, older.historyTime)
        assertEquals("""{"l":1}""", older.archiveJson)

        val newer = BackupMerge.mergeArchive(local, backup(historyTime = 30), null)!!
        assertEquals(30L, newer.historyTime)
        assertEquals("""{"b":1}""", newer.archiveJson)
        assertEquals(2, newer.historyMode)
        assertEquals(0.5f, newer.historyScrollFraction)
    }

    @Test
    fun the_earlier_favourite_time_is_kept() {
        val local = ArchiveLocalState(arcid = "a", serverProfileId = 7, archiveJson = "{}", favoriteTime = 50)
        assertEquals(40L, BackupMerge.mergeArchive(local, backup(favoriteTime = 40), null)!!.favoriteTime)
        assertEquals(50L, BackupMerge.mergeArchive(local, backup(favoriteTime = 60), null)!!.favoriteTime)
    }

    @Test
    fun a_download_comes_back_only_when_its_files_were_found() {
        val finished = backup(downloadState = DownloadState.FINISH.code)
        assertNull("no files, no history: nothing to restore", BackupMerge.mergeArchive(null, finished, null))

        val restored = BackupMerge.mergeArchive(null, finished, relink)!!
        assertEquals(DownloadState.FINISH, restored.downloadState)
        assertEquals("file:///dl", restored.downloadRootUri)
    }

    @Test
    fun an_unfinished_download_restarts_from_none() {
        val restored = BackupMerge.mergeArchive(null, backup(downloadState = DownloadState.DOWNLOAD.code), relink)!!
        assertEquals(DownloadState.NONE, restored.downloadState)
    }

    @Test
    fun a_local_download_is_never_replaced() {
        val local = ArchiveLocalState(
            arcid = "a", serverProfileId = 7, archiveJson = "{}",
            downloadState = DownloadState.WAIT, downloadRootUri = "file:///mine",
        )
        val merged = BackupMerge.mergeArchive(local, backup(downloadState = DownloadState.FINISH.code), relink)!!
        assertEquals(DownloadState.WAIT, merged.downloadState)
        assertEquals("file:///mine", merged.downloadRootUri)
    }

    @Test
    fun aggregates_take_the_larger_counts() {
        assertEquals(12L to 2, BackupMerge.mergeAggregate(12, 1, BackupDailyAggregate(1, 7, 9, 2)))
    }

    @Test
    fun reading_progress_takes_the_newer_save() {
        val b = BackupReadingProgress("a", page = 4, savedAt = 100)
        assertTrue(BackupMerge.backupProgressWins(localSavedAt = 0, hasLocal = false, backup = b))
        assertTrue(BackupMerge.backupProgressWins(localSavedAt = 50, hasLocal = true, backup = b))
        assertFalse(BackupMerge.backupProgressWins(localSavedAt = 100, hasLocal = true, backup = b))
    }
}
