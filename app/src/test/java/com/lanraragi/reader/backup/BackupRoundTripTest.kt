package com.lanraragi.reader.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ArchiveLocalState
import com.lanraragi.reader.dao.DailyReadingAggregate
import com.lanraragi.reader.dao.SearchHistoryEntry
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.download.DownloadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Audit C05 / ruling R18: a backup written on one device merges into another
 * device's data through the real DAOs, the file format and the prefs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupRoundTripTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase

    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also {
        it.edit().clear().commit()
    }

    private val srcSettings by lazy { prefs("src_settings") }
    private val srcProgress by lazy { prefs("src_progress") }
    private val dstSettings by lazy { prefs("dst_settings") }
    private val dstProgress by lazy { prefs("dst_progress") }

    @Before
    fun setUp() {
        source = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        target = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun seedSource(): Long = runBlocking {
        val home = source.miscDao().insertServerProfile(
            ServerProfile(name = "Home", url = "http://10.0.0.2:3000", isActive = true)
        )
        val dao = source.archiveLocalStateDao()
        dao.insertNew(ArchiveLocalState(arcid = "read", serverProfileId = home, archiveJson = """{"t":"r"}""", historyTime = 100))
        dao.insertNew(ArchiveLocalState(arcid = "fav", serverProfileId = home, archiveJson = "{}", favoriteTime = 50))
        for (arcid in listOf("ondisk", "gone")) {
            dao.insertNew(
                ArchiveLocalState(
                    arcid = arcid, serverProfileId = home, archiveJson = "{}",
                    downloadState = DownloadState.FINISH, downloadRootUri = "file:///old",
                )
            )
        }
        source.browsingDao().upsertSearchHistory(
            SearchHistoryEntry().apply { query = "q"; serverProfileId = home; lastUsed = 9 }
        )
        source.statsDao().insertDailyAggregateIfAbsent(
            DailyReadingAggregate().apply { epochDay = 1; serverProfileId = home; pagesRead = 30; completed = 1 }
        )
        srcSettings.edit().putInt("theme", 2).putString("image_path", "/x").commit()
        srcProgress.edit()
            .putInt("$home:read", 7).putLong("$home:read_ts", 200L)
            // A pre-C37 entry (bare arcid) belongs to the backup's active server.
            .putInt("old", 3).putLong("old_ts", 100L)
            .commit()
        home
    }

    private fun backupBytes(): ByteArray {
        val backup = runBlocking { BackupExporter(source, srcSettings, srcProgress).collect() }
        return ByteArrayOutputStream().also { BackupCodec.write(backup, it) }.toByteArray()
    }

    private fun importer() = BackupImporter(
        target, dstSettings, dstProgress,
        relinker = DownloadRelinker { root -> if (root == "file:///new") listOf("Title" to "ondisk") else emptyList() },
        currentRootUri = { "file:///new" },
    )

    @Test
    fun a_backup_restores_into_an_empty_device() {
        seedSource()
        val backup = BackupCodec.read(ByteArrayInputStream(backupBytes()))
        val result = runBlocking { importer().restore(backup) }

        assertEquals(1, result.profilesAdded)
        assertEquals(1, result.downloadsRelinked)
        assertEquals(1, result.downloadsSkipped)
        runBlocking {
            val profile = target.miscDao().getAllServerProfiles().single()
            assertEquals("http://10.0.0.2:3000", profile.url)
            assertEquals(false, profile.isActive)
            val dao = target.archiveLocalStateDao()
            assertEquals(100L, dao.loadByArcidAndProfile("read", profile.id)?.historyTime)
            assertEquals(50L, dao.loadByArcidAndProfile("fav", profile.id)?.favoriteTime)
            val relinked = dao.loadByArcidAndProfile("ondisk", profile.id)!!
            assertEquals(DownloadState.FINISH, relinked.downloadState)
            assertEquals("file:///new", relinked.downloadRootUri)
            assertEquals("Title", target.downloadDao().loadDirname("ondisk")?.dirname)
            assertNull("a download without files is not restored", dao.loadByArcidAndProfile("gone", profile.id))
            assertEquals(9L, target.browsingDao().getAllSearchHistory().single().lastUsed)
            assertEquals(30L, target.statsDao().getAllDailyAggregates().single().pagesRead)
        }
        assertEquals(2, dstSettings.getInt("theme", 0))
        assertTrue("the download location never travels", !dstSettings.contains("image_path"))
        val restored = runBlocking { target.miscDao().getAllServerProfiles().single().id }
        assertEquals(7, dstProgress.getInt("$restored:read", 0))
        assertEquals(3, dstProgress.getInt("$restored:old", 0))
    }

    @Test
    fun restoring_merges_with_local_data_and_is_idempotent() {
        seedSource()
        val local = runBlocking {
            val id = target.miscDao().insertServerProfile(
                ServerProfile(name = "Mine", url = "http://10.0.0.2:3000/", isActive = true)
            )
            target.archiveLocalStateDao().insertNew(
                ArchiveLocalState(arcid = "read", serverProfileId = id, archiveJson = """{"t":"local"}""", historyTime = 300)
            )
            target.statsDao().insertDailyAggregateIfAbsent(
                DailyReadingAggregate().apply { epochDay = 1; serverProfileId = id; pagesRead = 40; completed = 0 }
            )
            id
        }
        dstProgress.edit().putInt("$local:read", 9).putLong("$local:read_ts", 500L).commit()
        val backup = BackupCodec.read(ByteArrayInputStream(backupBytes()))

        runBlocking { importer().restore(backup) }
        runBlocking { importer().restore(backup) }

        runBlocking {
            val profiles = target.miscDao().getAllServerProfiles()
            assertEquals("the same server is matched, not added", 1, profiles.size)
            assertEquals("Mine", profiles.single().name)
            assertTrue(profiles.single().isActive)
            val read = target.archiveLocalStateDao().loadByArcidAndProfile("read", local)!!
            assertEquals("the newer local history stays", 300L, read.historyTime)
            assertEquals("""{"t":"local"}""", read.archiveJson)
            val day = target.statsDao().getAllDailyAggregates().single()
            assertEquals("larger count, never summed", 40L, day.pagesRead)
            assertEquals(1, day.completed)
            assertEquals(1, target.browsingDao().getAllSearchHistory().size)
        }
        assertEquals("the newer local progress stays", 9, dstProgress.getInt("$local:read", 0))
    }

    @Test
    fun a_restored_profile_reads_as_keyless_not_as_a_lost_key() {
        LRRAuthManager.initializeForTesting(prefs("auth_secure"))
        try {
            seedSource()
            val mine = runBlocking {
                target.miscDao().insertServerProfile(ServerProfile(name = "Mine", url = "https://mine.example"))
            }
            LRRAuthManager.setApiKeyForProfile(mine, "secret")
            val backup = BackupCodec.read(ByteArrayInputStream(backupBytes()))

            runBlocking { importer().restore(backup) }

            val profiles = runBlocking { target.miscDao().getAllServerProfiles() }
            val restored = profiles.single { it.id != mine }.id
            // The boot check of LRReaderApplication, run on the next launch.
            LRRAuthManager.markReauthIfProfilesUnprotected(profiles.map { it.id })
            assertFalse("a restore must not ask for reauthentication", LRRAuthManager.isNeedsReauthentication())
            assertNull("no key is sent for the restored server", LRRAuthManager.getApiKeyForProfile(restored))
            assertEquals("a local key is untouched", "secret", LRRAuthManager.getApiKeyForProfile(mine))
        } finally {
            LRRAuthManager.clear()
        }
    }

    @Test
    fun a_download_kept_under_another_local_profile_is_left_alone() {
        seedSource()
        val other = runBlocking {
            val id = target.miscDao().insertServerProfile(ServerProfile(name = "Other", url = "https://other.example"))
            target.archiveLocalStateDao().insertNew(
                ArchiveLocalState(
                    arcid = "ondisk", serverProfileId = id, archiveJson = "{}",
                    downloadState = DownloadState.FINISH, downloadRootUri = "file:///mine",
                )
            )
            id
        }
        val backup = BackupCodec.read(ByteArrayInputStream(backupBytes()))

        val result = runBlocking { importer().restore(backup) }

        assertEquals(0, result.downloadsRelinked)
        runBlocking {
            val downloads = target.archiveLocalStateDao().getAllDownloads()
            assertEquals("one download row per arcid", 1, downloads.size)
            assertEquals(other, downloads.single().serverProfileId)
            assertEquals("file:///mine", downloads.single().downloadRootUri)
        }
    }

    @Test(expected = BackupFormatException::class)
    fun another_json_file_is_refused() {
        BackupCodec.read(ByteArrayInputStream("""{"format":"something-else"}""".toByteArray()))
    }

    @Test(expected = BackupFormatException::class)
    fun a_backup_from_a_newer_app_is_refused() {
        BackupCodec.read(ByteArrayInputStream("""{"format":"lrreader-backup","version":99}""".toByteArray()))
    }
}
