package com.lanraragi.reader.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.download.DownloadState
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

    // ── reconcileTankGroup (spec 2026-09-21 §1) ─────────────────

    @Test
    fun reconcileTankGroup_addedMember_rewritesRowAndTagsIt() = runTest {
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))
        repo.putDownloadInfo(downloadInfo(ARC_B, "B"))
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A))

        val result = repo.reconcileTankGroup(TANK, "MyTank", listOf(ARC_A, ARC_B), activeProfileId = 1L)

        assertEquals(listOf(ARC_B), result!!.added)
        assertEquals(listOf(ARC_A, ARC_B), repo.getTankGroupMemberIds(TANK))
        val tags = repo.observeDownloads().first().associate { it.arcid to it.tankId }
        assertEquals(TANK, tags[ARC_A])
        assertEquals(TANK, tags[ARC_B])
    }

    @Test
    fun reconcileTankGroup_removedMember_untagsRow_keepsDownload() = runTest {
        repo.putDownloadInfo(downloadInfo(ARC_A, "A"))
        repo.putDownloadInfo(downloadInfo(ARC_B, "B"))
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A, ARC_B))

        val result = repo.reconcileTankGroup(TANK, "MyTank", listOf(ARC_A), activeProfileId = 1L)

        assertEquals(listOf(ARC_B), result!!.removed)
        assertEquals(listOf(ARC_A), repo.getTankGroupMemberIds(TANK))
        val rows = repo.observeDownloads().first()
        assertEquals(2, rows.size)
        assertNull(rows.single { it.arcid == ARC_B }.tankId)
        assertEquals(TANK, rows.single { it.arcid == ARC_A }.tankId)
    }

    @Test
    fun reconcileTankGroup_reorderAndRename_keepCreatedTime() = runTest {
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A, ARC_B))
        val created = repo.getTankGroup(TANK)!!.createdTime

        repo.reconcileTankGroup(TANK, "Renamed", listOf(ARC_B, ARC_A), activeProfileId = 1L)

        val group = repo.getTankGroup(TANK)!!
        assertEquals("Renamed", group.name)
        assertEquals(created, group.createdTime)
        assertEquals(listOf(ARC_B, ARC_A), repo.getTankGroupMemberIds(TANK))
    }

    @Test
    fun reconcileTankGroup_unchanged_returnsNull() = runTest {
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A, ARC_B))
        assertNull(repo.reconcileTankGroup(TANK, "MyTank", listOf(ARC_A, ARC_B), activeProfileId = 1L))
    }

    @Test
    fun reconcileTankGroup_unknownGroup_orOtherProfile_isNoOp() = runTest {
        assertNull(repo.reconcileTankGroup(TANK, "MyTank", listOf(ARC_A), activeProfileId = 1L))

        repo.putTankGroup(TANK, 2L, "MyTank", listOf(ARC_A))
        assertNull(repo.reconcileTankGroup(TANK, "Other", listOf(ARC_B), activeProfileId = 1L))
        assertEquals(listOf(ARC_A), repo.getTankGroupMemberIds(TANK))
    }

    // ── findTankGroupClaiming (spec 2026-09-21 §6) ──────────────

    @Test
    fun findTankGroupClaiming_matchesGroupRowMembership_activeProfileOnly() = runTest {
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A))

        assertEquals("MyTank", repo.findTankGroupClaiming(ARC_A, 1L)!!.name)
        assertNull(repo.findTankGroupClaiming(ARC_B, 1L))
        assertNull(repo.findTankGroupClaiming(ARC_A, 2L))
    }

    // ── pagecount persistence (tank card aggregate needs member totals) ──

    @Test
    fun putDownloadInfo_persistsPagecount_andKeepsItWhenALaterWriteHasNone() = runTest {
        repo.putDownloadInfo(downloadInfo(ARC_A, "A").also { it.pagecount = 42 })
        assertEquals(42, repo.observeDownloads().first().single().pagecount)

        repo.putDownloadInfo(downloadInfo(ARC_A, "A").also { it.state = DownloadState.FINISH })
        assertEquals(42, repo.observeDownloads().first().single().pagecount)

        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A))
        assertEquals(42, repo.getTankMemberArchives(TANK).single().pagecount)
    }

    private companion object {
        val ARC_A = "a".repeat(40)
        val ARC_B = "b".repeat(40)
        const val TANK = "TANK_1688000000"
    }

    @Test
    fun putTankGroup_fromASecondProfile_keepsTheOwningProfile() = runTest {
        repo.putTankGroup(TANK, 1L, "MyTank", listOf(ARC_A, ARC_B))
        // Another profile pointing at the same server downloads the same tank.
        repo.putTankGroup(TANK, 2L, "MyTank renamed", listOf(ARC_A, ARC_B))

        val group = repo.getTankGroup(TANK)!!
        assertEquals(1L, group.serverProfileId)
        assertEquals("MyTank renamed", group.name)
        assertEquals(group, repo.findTankGroupClaiming(ARC_A, 1L))
    }
}
