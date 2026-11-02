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
import com.lanraragi.reader.dao.DownloadState
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.settings.DownloadSettings

/**
 * The one entry for browse download requests — the user tapped "download"
 * on an archive's detail page or on a multi-selection in a gallery list.
 * Every request is classified by [DownloadEntryGate] (missing rows are
 * added, incomplete rows re-queued, FINISH rows skipped as already local)
 * and the work is handed to [DownloadService], which holds the foreground
 * notification and the wake/Wi-Fi locks for the download session.
 *
 * UI code only asks the user for a label when [enqueue] says so
 * ([Outcome.PickLabel]) and reports the [Outcome] back.
 *
 * Main thread: the [DownloadManager] reads and mutators used here are
 * main-thread confined.
 */
object DownloadRequests {

    /** Split of a request by the archives' current download state. */
    class Plan(
        val toAdd: List<Archive>,
        val toRestart: List<String>,
        val alreadyLocal: List<String>,
    )

    /** Pure split of [archives] by [DownloadEntryGate]; order within each part is kept. */
    fun plan(archives: List<Archive>, stateOf: (String) -> DownloadState): Plan {
        val toAdd = ArrayList<Archive>()
        val toRestart = ArrayList<String>()
        val alreadyLocal = ArrayList<String>()
        for (archive in archives) {
            when (DownloadEntryGate.disposition(stateOf(archive.arcid))) {
                DownloadEntryGate.Disposition.NEW -> toAdd.add(archive)
                DownloadEntryGate.Disposition.RESTART -> toRestart.add(archive.arcid)
                // FINISH rows stay untouched: re-queuing runs the worker
                // against the source profile's URL and an unreachable source
                // would flip a completed download to FAILED.
                DownloadEntryGate.Disposition.ALREADY_LOCAL -> alreadyLocal.add(archive.arcid)
            }
        }
        return Plan(toAdd, toRestart, alreadyLocal)
    }

    /** Which tip the caller shows once the request is queued. */
    enum class Feedback {
        /** "Added to the download list". */
        ADDED,

        /** "Already downloaded": nothing was added or re-queued, something was local. */
        ALREADY_LOCAL,
    }

    /** What [enqueue] did, and what is left for the caller. */
    sealed interface Outcome {
        /** Everything is queued; show [feedback]. */
        data class Done(val feedback: Feedback) : Outcome

        /**
         * New archives wait for a label the user picks from [labels]
         * (the existing labels, in order; the default label is not in it).
         * Call [addWithPickedLabel] with the choice.
         */
        class PickLabel(val toAdd: List<Archive>, val labels: List<String?>) : Outcome
    }

    /** Label for new downloads: use [Use.label] directly, or ask the user. */
    sealed interface LabelDecision {
        data class Use(val label: String?) : LabelDecision
        data object Ask : LabelDecision
    }

    /**
     * Label for new downloads without asking: [forceDefault] means the
     * default (null) label; a remembered default label is used while it
     * still exists (null = the default label); with no label at all there
     * is nothing to pick from.
     */
    fun decideLabel(
        forceDefault: Boolean,
        hasDefaultLabel: Boolean,
        defaultLabel: String?,
        containsLabel: (String) -> Boolean,
        labelCount: Int,
    ): LabelDecision {
        if (forceDefault) return LabelDecision.Use(null)
        if (hasDefaultLabel && (defaultLabel == null || containsLabel(defaultLabel))) {
            return LabelDecision.Use(defaultLabel)
        }
        if (labelCount == 0) return LabelDecision.Use(null)
        return LabelDecision.Ask
    }

    /**
     * Detail-page request: re-queues incomplete rows, adds missing ones with
     * the configured label, or returns [Outcome.PickLabel] when the user has
     * to choose one.
     */
    fun enqueue(
        context: Context,
        downloadManager: DownloadManager,
        archives: List<Archive>,
        forceDefault: Boolean,
    ): Outcome = enqueue(
        archives = archives,
        stateOf = downloadManager::getDownloadState,
        labelDecision = {
            decideLabel(
                forceDefault = forceDefault,
                hasDefaultLabel = DownloadSettings.getHasDefaultDownloadLabel(),
                defaultLabel = DownloadSettings.getDefaultDownloadLabel(),
                containsLabel = downloadManager::containLabel,
                labelCount = downloadManager.labelList.size,
            )
        },
        labels = { downloadManager.labelList.map { it.label } },
        send = context::startService,
        context = context,
    )

    /** [enqueue] with its lookups and the service hand-off injected. */
    internal fun enqueue(
        archives: List<Archive>,
        stateOf: (String) -> DownloadState,
        labelDecision: () -> LabelDecision,
        labels: () -> List<String?>,
        send: (Intent) -> Unit,
        context: Context,
    ): Outcome {
        val plan = plan(archives, stateOf)
        if (plan.toRestart.isNotEmpty()) send(restartIntent(context, plan.toRestart))
        if (plan.toAdd.isEmpty()) {
            val feedback = if (plan.toRestart.isEmpty() && plan.alreadyLocal.isNotEmpty()) {
                Feedback.ALREADY_LOCAL
            } else {
                Feedback.ADDED
            }
            return Outcome.Done(feedback)
        }
        return when (val decision = labelDecision()) {
            is LabelDecision.Use -> {
                for (archive in plan.toAdd) send(addIntent(context, archive, decision.label))
                Outcome.Done(Feedback.ADDED)
            }
            LabelDecision.Ask -> Outcome.PickLabel(plan.toAdd, labels())
        }
    }

    /**
     * Second half of [Outcome.PickLabel]: queue [toAdd] under [picked]
     * (null = default label; a label deleted meanwhile also falls back to
     * the default) and, when [remember] is set, keep it as the default
     * download label.
     */
    fun addWithPickedLabel(
        context: Context,
        downloadManager: DownloadManager,
        toAdd: List<Archive>,
        picked: String?,
        remember: Boolean,
    ) {
        val label = picked?.takeIf { downloadManager.containLabel(it) }
        for (archive in toAdd) context.startService(addIntent(context, archive, label))
        if (remember) {
            DownloadSettings.putHasDefaultDownloadLabel(true)
            DownloadSettings.putDefaultDownloadLabel(label)
        } else {
            DownloadSettings.putHasDefaultDownloadLabel(false)
        }
    }

    /** [DownloadService.ACTION_START_RANGE] for [arcids]. */
    internal fun restartIntent(context: Context, arcids: List<String>): Intent =
        Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START_RANGE)
            .putStringArrayListExtra(DownloadService.KEY_ARCID_LIST, ArrayList(arcids))

    /** [DownloadService.ACTION_START] for one new [archive] under [label]. */
    internal fun addIntent(context: Context, archive: Archive, label: String?): Intent =
        Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.KEY_LABEL, label)
            .putExtra(DownloadService.KEY_ARCHIVE, archive)
}
