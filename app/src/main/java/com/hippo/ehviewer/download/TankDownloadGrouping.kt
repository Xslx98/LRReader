package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.TankDownloadGroup
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Pure grouping step between the Room downloads flow and the downloads
 * list UI (Track 2): download rows claimed by a live tank group's MEMBER
 * ID LIST fold into ONE synthetic card row per group — the established
 * pseudo-row idiom (browse list's toTankArchive, history's tank row):
 * the card is a [DownloadInfo] whose arcid IS the TANK_ id, so the
 * adapter renders it with ordinary bindings and click sites branch on
 * isTankoubonId.
 *
 * Membership truth is the group row's id list (NOT the per-row
 * DOWNLOAD_TANK_ID tag): a member whose row is created after the group
 * was persisted folds in the moment it appears — no tag/enqueue race.
 * The row tag stays a best-effort mirror for future queries.
 *
 * Rules:
 * - An arcid claimed by several groups folds into the FIRST group
 *   (observeTankGroups order: newest first).
 * - A group with zero surviving member rows renders no card.
 * - Card state aggregates member states: any WAIT/DOWNLOAD → DOWNLOAD,
 *   else any FAILED → FAILED, else all FINISH → FINISH, else NONE.
 * - Card time = max member DOWNLOAD_TIME so the card sorts where its
 *   most recent member activity is; output keeps time-DESC order.
 */
object TankDownloadGrouping {

    private val json = Json { ignoreUnknownKeys = true }

    /** Grouping result: the display list plus per-tank member lists. */
    data class Result(
        val display: List<DownloadInfo>,
        val tankMembers: Map<String, List<DownloadInfo>>,
    )

    fun group(all: List<DownloadInfo>, groups: List<TankDownloadGroup>): Result {
        if (groups.isEmpty()) return Result(all, emptyMap())
        val groupsById = groups.associateBy { it.tankId }
        val claimedBy = HashMap<String, String>()
        for (group in groups) {
            for (arcid in memberIdsOf(group)) {
                claimedBy.putIfAbsent(arcid, group.tankId)
            }
        }
        if (claimedBy.isEmpty()) return Result(all, emptyMap())

        val members = LinkedHashMap<String, MutableList<DownloadInfo>>()
        val standalone = ArrayList<DownloadInfo>(all.size)
        for (info in all) {
            val tankId = claimedBy[info.arcid]
            if (tankId != null) {
                members.getOrPut(tankId) { ArrayList() }.add(info)
            } else {
                standalone.add(info)
            }
        }
        if (members.isEmpty()) return Result(all, emptyMap())

        val cards = members.map { (tankId, memberList) ->
            cardFor(groupsById.getValue(tankId), memberList)
        }
        val display = (standalone + cards).sortedByDescending { it.time }
        return Result(display, members)
    }

    private fun memberIdsOf(group: TankDownloadGroup): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), group.memberIdsJson)
    }.getOrDefault(emptyList())

    private fun cardFor(group: TankDownloadGroup, members: List<DownloadInfo>): DownloadInfo {
        val card = DownloadInfo()
        card.arcid = group.tankId
        card.tankId = group.tankId
        card.title = group.name
        card.thumb = members.firstOrNull { !it.thumb.isNullOrBlank() }?.thumb
        card.serverProfileId = group.serverProfileId
        card.time = members.maxOf { it.time }.coerceAtLeast(group.createdTime)
        card.state = aggregateState(members)
        return card
    }

    private fun aggregateState(members: List<DownloadInfo>): DownloadState = when {
        members.any { it.state == DownloadState.WAIT || it.state == DownloadState.DOWNLOAD } ->
            DownloadState.DOWNLOAD
        members.any { it.state == DownloadState.FAILED } -> DownloadState.FAILED
        members.all { it.state == DownloadState.FINISH } -> DownloadState.FINISH
        else -> DownloadState.NONE
    }
}
