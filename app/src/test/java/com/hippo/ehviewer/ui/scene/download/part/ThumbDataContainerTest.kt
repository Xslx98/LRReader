package com.hippo.ehviewer.ui.scene.download.part

import com.hippo.unifile.UniFile
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [ThumbDataContainer] (FW-2): the DataContainer callbacks run on
 * Conaco's single app-wide serial disk thread, so the download-dir future
 * must never be awaited unboundedly there, and pure read callbacks
 * (isEnabled/get) must not create files in the archive directory.
 */
class ThumbDataContainerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resolvedContainer(dir: File, waitMillis: Long = 1000L) =
        ThumbDataContainer(
            CompletableFuture.completedFuture(UniFile.fromFile(dir)),
            waitMillis
        )

    private fun thumbFile(dir: File) = File(dir, ".thumb")

    // ---- read path must not create files ----

    @Test
    fun isEnabled_true_withoutCreatingThumbFile() {
        val dir = tmp.newFolder()
        val container = resolvedContainer(dir)

        assertTrue(container.isEnabled)
        assertFalse(
            "read path must not create .thumb",
            thumbFile(dir).exists()
        )
    }

    @Test
    fun get_returnsNull_whenThumbMissing_andDoesNotCreateIt() {
        val dir = tmp.newFolder()
        val container = resolvedContainer(dir)

        assertNull(container.get())
        assertFalse(thumbFile(dir).exists())
    }

    @Test
    fun get_returnsExistingThumbContent() {
        val dir = tmp.newFolder()
        thumbFile(dir).writeBytes(byteArrayOf(1, 2, 3))
        val container = resolvedContainer(dir)

        val pipe = requireNotNull(container.get())
        pipe.obtain()
        try {
            val bytes = pipe.open().use { it.readBytes() }
            assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
        } finally {
            pipe.close()
            pipe.release()
        }
    }

    // ---- write path still creates the file ----

    @Test
    fun save_createsThumb_andGetReadsItBack() {
        val dir = tmp.newFolder()
        val container = resolvedContainer(dir)

        val saved = container.save(
            ByteArrayInputStream(byteArrayOf(9, 8, 7)), 3, null, null
        )

        assertTrue(saved)
        assertEquals(listOf<Byte>(9, 8, 7), thumbFile(dir).readBytes().toList())
        val pipe = requireNotNull(container.get())
        pipe.obtain()
        try {
            assertEquals(
                listOf<Byte>(9, 8, 7),
                pipe.open().use { it.readBytes() }.toList()
            )
        } finally {
            pipe.close()
            pipe.release()
        }
    }

    @Test
    fun remove_deletesExistingThumb_andIsNoopWhenMissing() {
        val dir = tmp.newFolder()
        thumbFile(dir).writeBytes(byteArrayOf(1))
        val container = resolvedContainer(dir)

        container.remove()
        assertFalse(thumbFile(dir).exists())

        // Second remove: nothing to delete, must not throw or create.
        container.remove()
        assertFalse(thumbFile(dir).exists())
    }

    // ---- bounded wait / disabled fallback ----

    @Test
    fun unresolvedFuture_disablesContainer_insteadOfBlocking() {
        val never = CompletableFuture<UniFile?>()
        val container = ThumbDataContainer(never, 10L)

        val startNanos = System.nanoTime()
        assertFalse(container.isEnabled)
        assertNull(container.get())
        assertFalse(
            container.save(ByteArrayInputStream(byteArrayOf(1)), 1, null, null)
        )
        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
        assertTrue(
            "bounded wait must stay far below the old unbounded get(), took ${elapsedMillis}ms",
            elapsedMillis < 5_000
        )
    }

    @Test
    fun boundedWait_happensOnlyOncePerContainer() {
        // Each callback re-waiting would re-stall the serial disk thread on
        // every isEnabled/get/save of a single load.
        var waits = 0
        val counting = object : CompletableFuture<UniFile?>() {
            override fun get(timeout: Long, unit: TimeUnit): UniFile? {
                waits++
                throw TimeoutException()
            }
        }
        val container = ThumbDataContainer(counting, 10L)

        container.isEnabled
        container.get()
        container.save(ByteArrayInputStream(byteArrayOf(1)), 1, null, null)

        assertEquals(1, waits)
    }

    @Test
    fun failedResolution_disablesContainer() {
        val failed = CompletableFuture<UniFile?>().apply {
            completeExceptionally(RuntimeException("resolver died"))
        }
        val container = ThumbDataContainer(failed, 1000L)

        assertFalse(container.isEnabled)
        assertNull(container.get())
    }

    @Test
    fun nullDirResolution_disablesContainer() {
        val container = ThumbDataContainer(
            CompletableFuture.completedFuture(null), 1000L
        )
        assertFalse(container.isEnabled)
    }

    @Test
    fun nonDirectoryResolution_disablesContainer() {
        val file = tmp.newFile()
        val container = ThumbDataContainer(
            CompletableFuture.completedFuture(UniFile.fromFile(file)), 1000L
        )
        assertFalse(container.isEnabled)
    }
}
