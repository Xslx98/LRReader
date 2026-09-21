/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.ui.scene.download

import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.dao.DownloadInfo

/**
 * Multi-select expansion for the downloads list (spec 2026-09-21 §4): a
 * checked tank card stands for its member rows, so batch start / stop /
 * delete act on the members while the TANK_ id itself never reaches the
 * service or scheduler. Label moves keep cards out ([rowsOnly]).
 */
internal object DownloadBatchSelection {

    class Expanded(
        /** Member rows of every checked card plus the checked plain rows, in selection order. */
        val infos: List<DownloadInfo>,
        /** Checked tank card ids, in selection order (for group dissolution after a delete). */
        val cardIds: List<String>,
        /** Checked plain rows only — the label-move subset. */
        val rowsOnly: List<DownloadInfo>,
    ) {
        val arcids: List<String> get() = infos.map { it.arcid }
        val hasCards: Boolean get() = cardIds.isNotEmpty()
    }

    fun expand(selected: List<DownloadInfo>, membersOf: (String) -> List<DownloadInfo>): Expanded {
        val infos = ArrayList<DownloadInfo>(selected.size)
        val cardIds = ArrayList<String>()
        val rows = ArrayList<DownloadInfo>(selected.size)
        for (info in selected) {
            if (isTankoubonId(info.arcid)) {
                cardIds.add(info.arcid)
                infos.addAll(membersOf(info.arcid))
            } else {
                infos.add(info)
                rows.add(info)
            }
        }
        return Expanded(infos, cardIds, rows)
    }
}
