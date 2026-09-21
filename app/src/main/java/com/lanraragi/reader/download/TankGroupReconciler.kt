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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Pure membership diff for a downloaded tank group against the server's
 * CURRENT member list (spec 2026-09-21 §1: the card follows the server).
 *
 * The stored group row is a snapshot taken at download time; whenever the
 * client obtains fresh tank membership (drawer tank list, tank detail) the
 * snapshot is brought in line: member id set, member ORDER (drives the
 * offline whole-tank page order) and display name all follow the server.
 *
 * Contract:
 * - A group owned by a profile other than [activeProfileId] is never
 *   reconciled (returns null) — "missing on server" is undecidable across
 *   profiles / outages, and the same tank id may live on several servers.
 * - Unchanged (same ordered ids, same name) → null, so callers skip the
 *   write and the downloads flow does not re-emit.
 * - Duplicate server ids collapse to their first occurrence.
 * - An empty server list empties the row (every member reported removed);
 *   the caller decides what an empty group renders as.
 * - Identity fields (tank id, profile, created time) are preserved.
 */
object TankGroupReconciler {

    private val json = Json { ignoreUnknownKeys = true }
    private val idsSerializer = ListSerializer(String.serializer())

    /** Rewritten row plus the membership delta the caller must mirror. */
    data class Result(
        val group: TankDownloadGroup,
        val added: List<String>,
        val removed: List<String>,
    )

    fun reconcile(
        group: TankDownloadGroup,
        serverName: String,
        serverMemberIds: List<String>,
        activeProfileId: Long,
    ): Result? {
        if (group.serverProfileId != activeProfileId) return null
        val stored = decode(group.memberIdsJson)
        val fresh = serverMemberIds.distinct()
        if (stored == fresh && group.name == serverName) return null
        val storedSet = stored.toHashSet()
        val freshSet = fresh.toHashSet()
        return Result(
            group = group.copy(name = serverName, memberIdsJson = encode(fresh)),
            added = fresh.filterNot { it in storedSet },
            removed = stored.filterNot { it in freshSet },
        )
    }

    /** Stored ordered member ids; corrupt JSON reads as an empty list. */
    fun decode(memberIdsJson: String): List<String> =
        runCatching { json.decodeFromString(idsSerializer, memberIdsJson) }
            .getOrDefault(emptyList())

    fun encode(memberIds: List<String>): String = json.encodeToString(idsSerializer, memberIds)
}
