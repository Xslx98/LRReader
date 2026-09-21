/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.download

import android.util.Log
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.CancellationException

/**
 * Applies fresh server tank membership to local state (spec 2026-09-21
 * §1/§3), once per tank the client just fetched:
 *
 * 1. the downloaded group row (if any) follows the server — ids, order,
 *    name — via [DownloadDbRepository.reconcileTankGroup];
 * 2. members' standalone history rows fold into the tank's TANK_ row via
 *    [HistoryRepository.foldMembersIntoTank]; on a fold the tank's local
 *    reading progress is seeded from the server's tank progress ONLY when
 *    the local store has none (a local tank session is fresher truth), the
 *    continue-reading shortcut is re-pointed at the tank when it targeted
 *    a folded member, and the widget re-renders (newest row is now the tank).
 *
 * One failing tank never blocks the others. Pure orchestration: every
 * side channel is injected so JVM tests drive it with fakes.
 */
class TankMembershipSync(
    private val downloadDb: DownloadDbRepository,
    private val history: HistoryRepository,
    private val progress: ProgressStore,
    private val shortcut: ShortcutPort,
    private val refreshWidget: () -> Unit,
) {

    /** SP reading-progress store seam (0-indexed pages, 0 = none). */
    interface ProgressStore {
        fun load(arcid: String): Int
        fun save(arcid: String, page0: Int)
    }

    /** Continue-reading shortcut seam. */
    interface ShortcutPort {
        /** (arcid, profileId) the shortcut currently deep-links to, or null. */
        fun currentTarget(): Pair<String, Long>?
        suspend fun publish(arcid: String, profileId: Long)
    }

    /** What the server just said about one tank. */
    data class TankTruth(
        val tankId: String,
        val name: String,
        val memberIds: List<String>,
        /** Server global progress, 1-based; 1 (or 0) = unread. */
        val progress: Int,
        /** Sum of member pagecounts when known, else 0. */
        val pagecount: Int,
    )

    /** Returns true when any local row changed (group rewrite or fold). */
    suspend fun sync(profileId: Long, baseUrl: String, tanks: List<TankTruth>): Boolean {
        var changed = false
        var folded = false
        for (tank in tanks) {
            try {
                if (downloadDb.reconcileTankGroup(tank.tankId, tank.name, tank.memberIds, profileId) != null) {
                    changed = true
                }
                val fold = history.foldMembersIntoTank(
                    tank.tankId, profileId, tank.memberIds, pseudoArchive(tank, profileId, baseUrl)
                ) ?: continue
                changed = true
                folded = true
                if (progress.load(tank.tankId) <= 0 && tank.progress > 1) {
                    progress.save(tank.tankId, tank.progress - 1)
                }
                val target = shortcut.currentTarget()
                if (target != null && target.second == profileId && target.first in fold.foldedArcids) {
                    shortcut.publish(tank.tankId, profileId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tank membership sync failed for one tank")
            }
        }
        if (folded) refreshWidget()
        return changed
    }

    private fun pseudoArchive(tank: TankTruth, profileId: Long, baseUrl: String) = Archive(
        arcid = tank.tankId,
        title = tank.name,
        tags = emptyMap(),
        pagecount = tank.pagecount.coerceAtLeast(0),
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = LRRTankoubonApi.getTankoubonThumbnailUrl(baseUrl, tank.tankId),
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = profileId,
    )

    private companion object {
        const val TAG = "TankMembershipSync"
    }
}
