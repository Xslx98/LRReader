/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.hippo.ehviewer.ui.gallery

import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.gallery.ReadingContext
import com.hippo.ehviewer.gallery.ReadingContextStore
import com.hippo.ehviewer.gallery.ReadingProgressSyncer
import com.hippo.ehviewer.gallery.TankPageMath
import com.lanraragi.reader.client.api.LRRTankoubonApi

/**
 * Mirrors the per-archive server progress sync at the TANKOUBON level: when
 * the current reader session was opened from a Tankoubon context, every page
 * shown also updates the tank's GLOBAL 1-indexed progress
 * (PUT /api/tankoubons/{id}/progress/{page}).
 *
 * Fire-and-forget by design: gated on ServerCapabilityCache inside
 * updateTankProgress, conflated/serialized by ReadingProgressSyncer (which
 * also swallows send failures) — reading must never notice.
 */
class TankoubonProgressSync(private val arcid: String) {

    private val syncer = ReadingProgressSyncer(
        ServiceRegistry.coroutineModule.ioScope
    ) sync@{ page0 ->
        val ctx = ReadingContextStore.currentFor(arcid) as? ReadingContext.Tankoubon ?: return@sync
        val idx = ctx.orderedMemberIds.indexOf(arcid)
        if (idx < 0 || idx >= ctx.pageOffsets.size - 1) return@sync
        val globalPage1 = TankPageMath.globalPage1(ctx.pageOffsets, idx, page0)
        LRRTankoubonApi.updateTankProgress(
            ServiceRegistry.networkModule.okHttpClient,
            ctx.sourceBaseUrl,
            ctx.tankId,
            globalPage1,
        )
    }

    /** Main thread, every page change (mirrors the continuation onPageShown feed). */
    fun onPageShown(page0: Int) {
        // cheap pre-check so non-tank sessions never enqueue coroutine work
        if (ReadingContextStore.currentFor(arcid) !is ReadingContext.Tankoubon) return
        syncer.submit(page0)
    }
}
