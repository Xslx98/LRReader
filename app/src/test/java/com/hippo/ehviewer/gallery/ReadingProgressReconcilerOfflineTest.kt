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
}
