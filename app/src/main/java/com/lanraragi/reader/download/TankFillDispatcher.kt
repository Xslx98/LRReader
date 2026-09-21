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

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import com.lanraragi.reader.R
import com.lanraragi.reader.domain.Archive

/**
 * "Fill the tank" — the ONE download path for a whole tankoubon, shared by
 * the tank detail overflow, the downloads card start control and the
 * drawer long-press (spec 2026-09-21 §4). Members ride the ordinary
 * [DownloadService] intents so worker / notification / resume behavior is
 * byte-identical to single downloads; the group row is (re)tagged so the
 * downloads list folds them into the tank card.
 *
 * Split ([plan]) mirrors [DownloadEntryGate]: FINISH rows are grouped only
 * (zero re-download), partial / failed rows restart, missing rows enqueue.
 */
object TankFillDispatcher {

    /** Outcome of [plan]: what to enqueue, what to restart, what is already on disk. */
    class Plan(
        val toAdd: List<Archive>,
        val toRestart: List<String>,
        val alreadyLocal: Int,
    ) {
        val queued: Int get() = toAdd.size + toRestart.size
    }

    /** Pure split of [members] by their current download state. */
    fun plan(members: List<Archive>, stateOf: (String) -> DownloadState): Plan {
        val toAdd = ArrayList<Archive>()
        val toRestart = ArrayList<String>()
        var alreadyLocal = 0
        for (member in members) {
            when (DownloadEntryGate.disposition(stateOf(member.arcid))) {
                DownloadEntryGate.Disposition.NEW -> toAdd.add(member)
                DownloadEntryGate.Disposition.RESTART -> toRestart.add(member.arcid)
                DownloadEntryGate.Disposition.ALREADY_LOCAL -> alreadyLocal++
            }
        }
        return Plan(toAdd, toRestart, alreadyLocal)
    }

    /**
     * Main thread. Fire the service intents for [plan] and (re)tag the group
     * with the tank's FULL ordered membership so late rows fold in.
     */
    fun dispatch(
        context: Context,
        downloadManager: DownloadManager,
        plan: Plan,
        tankId: String,
        tankName: String,
        profileId: Long,
        memberIdsInOrder: List<String>,
    ) {
        if (plan.toRestart.isNotEmpty()) {
            val intent = Intent(context, DownloadService::class.java)
            intent.action = DownloadService.ACTION_START_RANGE
            intent.putStringArrayListExtra(DownloadService.KEY_ARCID_LIST, ArrayList(plan.toRestart))
            context.startService(intent)
        }
        for (member in plan.toAdd) {
            val intent = Intent(context, DownloadService::class.java)
            intent.action = DownloadService.ACTION_START
            intent.putExtra(DownloadService.KEY_ARCHIVE, member)
            context.startService(intent)
        }
        downloadManager.tagTankDownloadGroup(tankId, tankName, profileId, memberIdsInOrder)
    }

    /** User feedback line, mirroring the batch-download wording. */
    fun feedback(resources: Resources, plan: Plan): String = when {
        plan.queued == 0 && plan.alreadyLocal > 0 ->
            resources.getQuantityString(R.plurals.batch_download_all_local, plan.alreadyLocal, plan.alreadyLocal)
        plan.alreadyLocal == 0 ->
            resources.getQuantityString(R.plurals.batch_download_queued, plan.queued, plan.queued)
        else ->
            resources.getString(R.string.batch_download_queued_some_local, plan.queued, plan.alreadyLocal)
    }
}
