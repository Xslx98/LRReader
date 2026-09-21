/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.gallery

import android.content.Context
import android.content.Intent
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.resolveSourceBaseUrl
import kotlinx.coroutines.CancellationException

/**
 * History member-row redirect (spec 2026-09-21 §5): an archive row whose
 * archive currently belongs to a tankoubon on its source server opens the
 * WHOLE-TANK session positioned on that member instead of a standalone
 * detail page. Anything that prevents a confident answer — no tank, an
 * unreachable server, a malformed reply, a failed resume build — yields
 * null so the caller falls back to the ordinary detail navigation (never a
 * dead click).
 */
object HistoryTankRedirect {

    /** First tank id containing the archive per [fetchTankIds], null on none / failure. */
    suspend fun memberTankId(fetchTankIds: suspend () -> List<String>): String? = try {
        fetchTankIds().firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Whole-tank resume intent anchored on [arcid], or null → open the detail page. */
    suspend fun resumeIntentOrNull(context: Context, arcid: String, profileId: Long): Intent? {
        val tankId = memberTankId {
            val url = resolveSourceBaseUrl(profileId, ServiceRegistry.dataModule.profileLookupCache)
            LRRTankoubonApi.getArchiveTankoubons(ServiceRegistry.networkModule.okHttpClient, url, arcid)
        } ?: return null
        return try {
            TankSessionRouter.buildResumeIntent(context, tankId, profileId, anchorArcid = arcid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
