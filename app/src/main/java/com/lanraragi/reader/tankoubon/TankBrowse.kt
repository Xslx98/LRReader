package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.LRRTankoubonApi.Tankoubon
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.domain.parseLrrTagString
import com.lanraragi.reader.domain.parseRatingFromTags

/**
 * Order and row mapping of the gallery's tanks-only mode
 * (docs/plan-2.4.0-tank-ux.md, PR-B). The server cannot search or sort
 * tanks, so both run here over the full list. A query filters and ranks
 * exactly like the picker sheet ([TankPickerRows]) whatever the sort.
 */
object TankBrowse {

    enum class Sort { NAME, MEMBERS, RECENT_READ }

    /**
     * @param lastRead tankId → last local read time (the `TANK_` history row);
     *   tanks without one sort after every read tank under [Sort.RECENT_READ]
     */
    fun order(tanks: List<Tankoubon>, query: String, sort: Sort, lastRead: Map<String, Long>): List<Tankoubon> {
        if (query.isNotBlank()) {
            return TankPickerRows.build(tanks, emptyList(), emptyList(), query).map { it.tank }
        }
        val byName = Comparator<Tankoubon> { a, b -> TankPickerRows.compareNames(a.name, b.name) }
        val comparator = when (sort) {
            Sort.NAME -> byName
            Sort.MEMBERS -> compareByDescending<Tankoubon> { it.archives.size }.then(byName)
            Sort.RECENT_READ -> compareByDescending<Tankoubon> { lastRead[it.id] ?: 0L }.then(byName)
        }
        return tanks.sortedWith(comparator)
    }

    /**
     * The tank as a gallery row, shaped like a server-folded `TANK_` entry
     * ([com.lanraragi.reader.client.api.data.LRRArchive.toTankArchive]) so the
     * adapter, click routing and selection treat it the same way.
     *
     * @param coverOverride a stand-in cover (first member's) for a tank whose
     *   generated cover is missing; null = the tank thumbnail route
     */
    fun toArchive(tank: Tankoubon, profileId: Long, baseUrl: String, coverOverride: String?, lastRead: Long): Archive =
        Archive(
            arcid = tank.id,
            title = tank.name,
            tags = parseLrrTagString(tank.tags.orEmpty()),
            pagecount = 0,
            progress = tank.progress,
            extension = "",
            filename = "",
            thumbnailUrl = coverOverride ?: LRRTankoubonApi.getTankoubonThumbnailUrl(baseUrl, tank.id),
            rating = parseRatingFromTags(tank.tags),
            isnew = false,
            lastreadtime = lastRead,
            summary = tank.summary,
            serverProfileId = profileId,
        )
}
