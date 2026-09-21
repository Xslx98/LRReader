package com.lanraragi.reader.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.domain.Archive
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TankMemberRouting.resolve] mirrors the standalone reader's routing rule:
 * complete local copy → Dir; any local dir (or a tracked download's pending
 * dir) with network up → streaming in hybrid mode over that dir; a local
 * dir offline → Dir even when incomplete; nothing local → plain streaming.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TankMemberRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: Context
    private val client = OkHttpClient()
    private val member = TankMemberSeed(arcid = "a".repeat(40), title = "Vol 1", pagecount = 3)

    private class FakeResolver(
        private val local: File?,
        private val pending: File?,
        private val complete: Boolean,
    ) : DownloadDirResolver {
        override suspend fun localDownloadDir(context: Context, archive: Archive): File? = local
        override suspend fun pendingDownloadDir(archive: Archive): File? = pending
        override fun isLocalCopyComplete(dir: File, expectedPages: Int): Boolean = complete
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ServiceRegistry.initializeForTest()
    }

    private fun resolve(resolver: DownloadDirResolver, online: Boolean) = runBlocking {
        TankMemberRouting.resolve(
            ctx, member, profileId = 1L, serverUrl = "http://mock",
            pageClient = client, listClient = client,
            resolver = resolver, networkAvailable = { online },
        )
    }

    @Test
    fun `complete local copy reads from the directory`() {
        val dir = tmp.newFolder("complete")
        val source = resolve(FakeResolver(dir, null, complete = true), online = true)
        assertTrue(source is DirTankMemberSource)
    }

    @Test
    fun `incomplete local copy with network streams in hybrid mode over that directory`() {
        val dir = tmp.newFolder("partial")
        val source = resolve(FakeResolver(dir, null, complete = false), online = true)
        assertTrue(source is LrrTankMemberSource)
        assertEquals(dir, (source as LrrTankMemberSource).store?.downloadDir)
    }

    @Test
    fun `tracked download without pages yet streams in hybrid mode over the pending directory`() {
        val pending = File(tmp.root, "pending-not-created")
        val source = resolve(FakeResolver(null, pending, complete = false), online = true)
        assertTrue(source is LrrTankMemberSource)
        assertEquals(pending, (source as LrrTankMemberSource).store?.downloadDir)
    }

    @Test
    fun `incomplete local copy offline reads from the partial directory`() {
        val dir = tmp.newFolder("partial-offline")
        val source = resolve(FakeResolver(dir, null, complete = false), online = false)
        assertTrue(source is DirTankMemberSource)
    }

    @Test
    fun `no download row streams plainly`() {
        val source = resolve(FakeResolver(null, null, complete = false), online = true)
        assertTrue(source is LrrTankMemberSource)
        assertNull((source as LrrTankMemberSource).store)
    }
}
