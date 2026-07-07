/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReadingProgressReconcilerOfflineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `no local progress falls to the snapshot page`() {
        // Fresh SP (page=0, ts=0); snapshot read long ago -> delta > grace -> server page wins.
        // This is the audit scenario: SP lost, Room snapshot knows page 33.
        assertEquals(32, ReadingProgressReconciler.resolveOffline(context, "arc-a", 33, 1_000L))
    }

    @Test
    fun `no snapshot keeps the local page`() {
        GalleryProvider2.saveReadingProgress(context, "arc-b", 7)
        assertEquals(7, ReadingProgressReconciler.resolveOffline(context, "arc-b", 0, 0L))
    }

    @Test
    fun `stale snapshot loses to newer local progress`() {
        GalleryProvider2.saveReadingProgress(context, "arc-c", 9) // ts = wall-clock now
        // Snapshot ts near epoch -> delta << -grace, local page > 0 -> local wins.
        assertEquals(9, ReadingProgressReconciler.resolveOffline(context, "arc-c", 5, 1_000L))
    }

    @Test
    fun `milliseconds snapshot timestamp is normalized - fresher local still wins`() {
        GalleryProvider2.saveReadingProgress(context, "arc-d", 30) // ts = wall-clock now (seconds)
        // History-path snapshots stamp System.currentTimeMillis(). Raw, the
        // ms value dwarfs the seconds SP ts (delta >> grace) and the stale
        // snapshot page 8 would beat the newer local page 30. Normalized to
        // seconds, an OLD ms snapshot loses as it should.
        val staleMsSnapshotTs = 1_000_000L * 1000L // epoch 1_000_000s, in ms
        assertEquals(
            30,
            ReadingProgressReconciler.resolveOffline(context, "arc-d", 8, staleMsSnapshotTs)
        )
    }
}
