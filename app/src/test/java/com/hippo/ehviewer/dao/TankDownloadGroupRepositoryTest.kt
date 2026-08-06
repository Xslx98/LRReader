package com.hippo.ehviewer.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.download.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tank download grouping data layer (Track 2): group CRUD, member
 * tagging through the downloads observer, ordered offline snapshots,
 * and dissolution semantics (untag, never delete).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankDownloadGroupRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadDbRepository

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = DownloadDbRepository(
            db.archiveLocalStateDao(), db.downloadDao(), db, Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun downloadInfo(arcid: String, title: String) = DownloadInfo().also {
        it.arcid = arcid
        it.title = title
        it.state = DownloadState.FINISH
        it.time = arcid.hashCode().toLong()
        it.serverProfileId = 1L
    }

    @Test
    fun putTankGroup_tagsMembers_andObserverCarriesTankId() = runTest {
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))
        repo.putDownloadInfo(downloadInfo(ARC_B, "B"))

        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A, ARC_B))

        val downloads = repo.observeDownloads().first()
        assertEquals(setOf(TANK), downloads.map { it.tankId }.toSet())

        val groups = repo.observeTankGroups().first()
        assertEquals(1, groups.size)
        assertEquals("MyTank", groups.single().name)
    }

    @Test
    fun getTankMemberArchives_returnsStoredTankOrder() = runTest {
        // Insert in reverse so DOWNLOAD_TIME ordering disagrees with tank order.
        repo.putDownloadInfo(downloadInfo(ARC_B, "B"))
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A, ARC_B))

        val members = repo.getTankMemberArchives(TANK)
        assertEquals(listOf(ARC_A, ARC_B), members.map { it.arcid })
        assertEquals(listOf("A", "B"), members.map { it.title })
    }

    @Test
    fun dissolveTankGroup_untagsMembers_keepsDownloadRows() = runTest {
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A))

        repo.dissolveTankGroup(TANK)

        val downloads = repo.observeDownloads().first()
        assertEquals(1, downloads.size)
        assertNull("member must reappear standalone", downloads.single().tankId)
        assertNull(repo.getTankGroup(TANK))
        assertTrue(repo.getTankMemberArchives(TANK).isEmpty())
    }

    @Test
    fun removeTankGroupMember_untagsRow_andShrinksTheGroupList() = runTest {
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))
        repo.putDownloadInfo(downloadInfo(ARC_B, "B"))
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A, ARC_B))

        repo.removeTankGroupMember(TANK, ARC_A)

        val byArcid = repo.observeDownloads().first().associateBy { it.arcid }
        assertNull(byArcid.getValue(ARC_A).tankId)
        assertEquals(TANK, byArcid.getValue(ARC_B).tankId)
        // Membership truth is the group list — the removed member drops out.
        assertEquals(listOf(ARC_B), repo.getTankGroupMemberIds(TANK))
        assertEquals(listOf(ARC_B), repo.getTankMemberArchives(TANK).map { it.arcid })
    }

    @Test
    fun removeTankGroupMember_lastMember_dropsTheGroup() = runTest {
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A))

        repo.removeTankGroupMember(TANK, ARC_A)

        assertNull(repo.getTankGroup(TANK))
    }

    @Test
    fun getTankMemberArchives_findsMembersEnqueuedAfterTheGroupRow() = runTest {
        // Group persisted BEFORE the member row exists (the enqueue race the
        // group-list membership design eliminates).
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A))
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))

        assertEquals(listOf(ARC_A), repo.getTankMemberArchives(TANK).map { it.arcid })
    }

    @Test
    fun normalDownloadUpsert_neverClobbersTankTag() = runTest {
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A))

        // A later state-transition upsert (worker progress etc.) must keep the tag.
        repo.putDownloadInfo(downloadInfo(ARC_A, "A").also { it.state = DownloadState.NONE })

        assertEquals(TANK, repo.observeDownloads().first().single().tankId)
    }

    private companion object {
        val ARC_A = "a".repeat(40)
        val ARC_B = "b".repeat(40)
        const val TANK = "TANK_1688000000"
    }
}
