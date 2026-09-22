package com.lanraragi.reader.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.framework.unifile.UniFile
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.DownloadDbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadDirAllocatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadDbRepository
    private lateinit var root: File
    private lateinit var allocator: DownloadDirAllocator

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repo = DownloadDbRepository(db.archiveLocalStateDao(), db.downloadDao(), db, Dispatchers.Unconfined)
        root = tmp.newFolder("download")
        allocator = DownloadDirAllocator(repo) { UniFile.fromFile(root) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun find_withoutPointer_returnsNullAndMintsNothing() = runTest {
        assertNull(allocator.find("a1"))
        assertNull(repo.getDownloadDirname("a1"))
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun allocate_createsDirectoryThenPersistsPointer() = runTest {
        val dir = allocator.allocate("a1", "Vol 1")
        assertNotNull(dir)
        assertTrue(File(root, "Vol 1").isDirectory)
        assertEquals("Vol 1", repo.getDownloadDirname("a1"))
        assertEquals(File(root, "Vol 1").absolutePath, allocator.find("a1")!!.uri.path)
    }

    @Test
    fun allocate_isIdempotentForTheSameArcid() = runTest {
        allocator.allocate("a1", "Vol 1")
        allocator.allocate("a1", "Vol 1")
        assertEquals("Vol 1", repo.getDownloadDirname("a1"))
        assertEquals(listOf("Vol 1"), root.list()!!.toList())
    }

    @Test
    fun allocate_avoidsNamesClaimedByOtherPointersEvenWithoutADirectory() = runTest {
        // A pointer whose directory was never created (pre-fix read paths).
        repo.putDownloadDirname("other", "Vol 1")
        allocator.allocate("a1", "Vol 1")
        assertEquals("Vol 1 (2)", repo.getDownloadDirname("a1"))
    }

    @Test
    fun allocate_treatsClaimedNamesCaseInsensitively() = runTest {
        repo.putDownloadDirname("other", "vol 1")
        allocator.allocate("a1", "Vol 1")
        assertEquals("Vol 1 (2)", repo.getDownloadDirname("a1"))
    }

    @Test
    fun concurrentAllocationsOfTheSameTitleGetDistinctDirectories() = runTest {
        val names = (1..8).map { i ->
            async(Dispatchers.Default) { allocator.allocate("a$i", "Same Title") }
        }.awaitAll().map { it!!.name }
        assertEquals(8, names.toSet().size)
        assertEquals(8, (1..8).mapNotNull { repo.getDownloadDirname("a$it") }.toSet().size)
    }

    @Test
    fun unsafeStoredPointerIsTreatedAsAbsent() = runTest {
        repo.putDownloadDirname("a1", "..")
        assertNull(allocator.find("a1"))
        val dir = allocator.allocate("a1", "Real")
        assertNotEquals(root.parentFile!!.absolutePath, dir!!.uri.path)
        assertEquals("Real", repo.getDownloadDirname("a1"))
    }
}
