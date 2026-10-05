package com.lanraragi.reader.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Audit SEC-12 / ruling R21: signer check before install, cleanup after it. */
class ApkSignerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun an_apk_sharing_the_installed_certificate_matches() {
        assertTrue(ApkSigner.sameSigner(setOf("aa"), setOf("aa")))
        // A rotated archive still lists the installed (older) certificate in its history.
        assertTrue(ApkSigner.sameSigner(setOf("aa"), setOf("aa", "bb")))
    }

    @Test
    fun another_key_or_an_unreadable_side_does_not_match() {
        assertFalse(ApkSigner.sameSigner(setOf("aa"), setOf("bb")))
        assertFalse(ApkSigner.sameSigner(setOf("aa"), emptySet()))
        assertFalse(ApkSigner.sameSigner(emptySet(), emptySet()))
    }

    @Test
    fun only_updates_downloaded_before_the_running_install_are_stale() {
        val dir = tmp.newFolder("updates")
        val installed = tmp.newFile("updates/LRReader-v1.apk").apply { setLastModified(1_000_000L) }
        val pending = tmp.newFile("updates/LRReader-v2.apk").apply { setLastModified(3_000_000L) }

        val stale = ApkSigner.staleUpdates(dir, lastUpdateTime = 2_000_000L)

        assertEquals(listOf(installed), stale)
        assertTrue(pending.exists())
    }
}
