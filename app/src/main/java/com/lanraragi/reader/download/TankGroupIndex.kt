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

import com.lanraragi.reader.dao.TankDownloadGroup

/**
 * arcid → downloaded-tank lookup built from the group rows (membership
 * truth = MEMBER_IDS_JSON, same rule as [TankDownloadGrouping]: an arcid
 * claimed by several groups belongs to the first). [DownloadManager]
 * keeps the latest index so the download service can treat a tank as ONE
 * download unit (notification title / aggregate progress / single
 * completion) from its main-thread callbacks.
 */
object TankGroupIndex {

    data class Ref(val tankId: String, val name: String, val memberIds: List<String>)

    fun build(groups: List<TankDownloadGroup>): Map<String, Ref> {
        if (groups.isEmpty()) return emptyMap()
        val out = HashMap<String, Ref>()
        for (group in groups) {
            val ids = TankGroupReconciler.decode(group.memberIdsJson)
            if (ids.isEmpty()) continue
            val ref = Ref(group.tankId, group.name, ids)
            for (id in ids) out.putIfAbsent(id, ref)
        }
        return out
    }
}
