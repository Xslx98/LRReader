package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.LRRTankoubonApi.Tankoubon

/**
 * Row model of the tank picker sheet (docs/plan-2.4.0-tank-ux.md, PR-A).
 * The server's tank list has no search or sort, so both run here over the
 * full list: a few hundred rows of metadata.
 *
 * Without a query the rows come in sections — pinned memberships (only in
 * membership mode), then the recently used tanks, then every other tank in
 * natural name order — and no tank appears twice. With a query the sections
 * merge into one ranked list: name prefix, then name infix, then a match in
 * the tank's own summary or tags. Member titles are never searched: the list
 * endpoint does not carry them.
 */
object TankPickerRows {

    enum class Section { MEMBER, RECENT, ALL, MATCH }

    /**
     * @property contained how many of the selected archives the tank already holds
     * @property containsAll the selection is non-empty and entirely inside the tank
     */
    data class Row(
        val tank: Tankoubon,
        val section: Section,
        val contained: Int,
        val containsAll: Boolean,
    )

    fun build(
        tanks: List<Tankoubon>,
        selection: Collection<String>,
        recentIds: List<String>,
        query: String,
        pinMembers: Boolean = false,
    ): List<Row> {
        val selected = selection.toSet()
        fun row(tank: Tankoubon, section: Section): Row {
            val contained = if (selected.isEmpty()) 0 else tank.archives.count { it in selected }
            return Row(tank, section, contained, selected.isNotEmpty() && contained == selected.size)
        }

        val needle = query.trim().lowercase()
        if (needle.isNotEmpty()) {
            return tanks
                .mapNotNull { tank -> matchRank(tank, needle)?.let { it to tank } }
                .sortedWith(compareBy<Pair<Int, Tankoubon>> { it.first }.thenComparator { a, b -> byName(a.second, b.second) })
                .map { row(it.second, Section.MATCH) }
        }

        val rows = ArrayList<Row>(tanks.size)
        val placed = HashSet<String>()
        if (pinMembers && selected.isNotEmpty()) {
            val members = tanks.filter { tank -> tank.archives.any { it in selected } }.sortedWith(::byName)
            for (tank in members) {
                rows.add(row(tank, Section.MEMBER))
                placed.add(tank.id)
            }
        }
        val byId = tanks.associateBy { it.id }
        for (id in recentIds) {
            val tank = byId[id]
            if (tank != null && placed.add(id)) rows.add(row(tank, Section.RECENT))
        }
        for (tank in tanks.filter { it.id !in placed }.sortedWith(::byName)) {
            rows.add(row(tank, Section.ALL))
        }
        return rows
    }

    /** True when a tank named [name] (case- and blank-insensitive) already exists. */
    fun hasExactName(tanks: List<Tankoubon>, name: String): Boolean {
        val wanted = name.trim().lowercase()
        return wanted.isNotEmpty() && tanks.any { it.name.trim().lowercase() == wanted }
    }

    private fun matchRank(tank: Tankoubon, needle: String): Int? {
        val name = tank.name.lowercase()
        return when {
            name.startsWith(needle) -> RANK_PREFIX
            needle in name -> RANK_INFIX
            tank.summary?.lowercase()?.contains(needle) == true -> RANK_META
            tank.tags?.lowercase()?.contains(needle) == true -> RANK_META
            else -> null
        }
    }

    /** Natural, case-insensitive tank-name order ([TankTitleSortKey.compareNatural] alone is case-sensitive). */
    fun compareNames(a: String, b: String): Int {
        val c = TankTitleSortKey.compareNatural(a.lowercase(), b.lowercase())
        return if (c != 0) c else TankTitleSortKey.compareNatural(a, b)
    }

    private fun byName(a: Tankoubon, b: Tankoubon): Int = compareNames(a.name, b.name)

    private const val RANK_PREFIX = 0
    private const val RANK_INFIX = 1
    private const val RANK_META = 2
}
