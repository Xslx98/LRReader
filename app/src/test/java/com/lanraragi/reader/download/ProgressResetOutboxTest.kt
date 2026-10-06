package com.lanraragi.reader.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.gallery.ArchiveProgressOutbox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Audit 2026-10-06 C20: "Reset reading progress" must not be undone by a page
 * still waiting in [ArchiveProgressOutbox] from before the reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ProgressResetOutboxTest {

    private val base = "http://192.168.1.10:3000"
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("reset_outbox_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ArchiveProgressOutbox.installForTesting(prefs)
        ArchiveProgressOutbox.clockSeconds = { now }
    }

    @After
    fun tearDown() {
        ArchiveProgressOutbox.installForTesting(null)
        ArchiveProgressOutbox.clockSeconds = { System.currentTimeMillis() / 1000L }
    }

    @Test
    fun `a successful reset drops the page pending from before it`() = runBlocking {
        ArchiveProgressOutbox.markPending(base, "arc", 40)
        now += 60

        DownloadManager.pushProgressReset(base, "arc") {}

        assertTrue(ArchiveProgressOutbox.entries().isEmpty())
    }

    @Test
    fun `a failed reset replaces the pending page with the first page`() = runBlocking {
        ArchiveProgressOutbox.markPending(base, "arc", 40)
        now += 60

        runCatching { DownloadManager.pushProgressReset(base, "arc") { throw IOException("offline") } }

        assertEquals(listOf(ArchiveProgressOutbox.Entry(base, "arc", 0, now)), ArchiveProgressOutbox.entries())
    }
}
