package com.lanraragi.reader.archive

import com.lanraragi.reader.client.api.LRRSearchApi
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.server.TankoubonSupportGate
import com.lanraragi.reader.settings.AppearanceSettings

/**
 * One page of archives from the LANraragi search API (GET /api/search).
 *
 * Pages are addressed by **raw item offset** into the server-side result set,
 * not by a dense page index: /api/search has no page-size parameter — the
 * server returns `archives_per_page` rows per request — so the only safe
 * advance is by the count actually returned, and the only safe termination
 * signal is `recordsFiltered` (audit NET-2; index-based math silently
 * truncated the list when the server page was smaller than 50 and duplicated
 * rows when larger).
 *
 * @param includeTanks whether to fold tankoubons into the list
 *   (`groupby_tanks` + `TANK_` pseudo-entries in the page) for the server at
 *   the given base URL. Evaluated once per load so a settings toggle applies
 *   on the next refresh. Default: fold per user setting, unless this server is
 *   known pre-0.9.8 ([TankoubonSupportGate] flips after the first failed tank
 *   open). Injectable because the default reads SharedPreferences-backed
 *   settings, which plain JVM unit tests cannot touch.
 * @param hideCompleted whether to ask the server to hide completed archives
 *   (same evaluation and injection rules)
 */
class ArchiveSearchSource(
    private val includeTanks: (baseUrl: String) -> Boolean = { baseUrl ->
        AppearanceSettings.getGroupTanks() && !TankoubonSupportGate.isUnsupported(baseUrl)
    },
    private val hideCompleted: () -> Boolean = { AppearanceSettings.getHideCompleted() },
) {

    /** The `/api/search` inputs (null = server default / no filter). */
    data class Query(
        val filter: String? = null,
        val category: String? = null,
        val sortby: String? = null,
        val order: String? = null,
        val newonly: Boolean = false,
        val untaggedonly: Boolean = false,
    )

    /**
     * @param items the page's archives (tank entries mapped or dropped per the fold decision)
     * @param nextOffset the raw offset of the first unseen row, or null when the list ends here
     */
    data class Page(val items: List<Archive>, val nextOffset: Int?)

    /**
     * Loads the page starting at raw [offset]. Failures (network, HTTP,
     * parse) propagate as exceptions; cancellation is never swallowed.
     */
    suspend fun load(conn: ServerConnection, query: Query, offset: Int): Page {
        val fold = includeTanks(conn.baseUrl)
        val result = LRRSearchApi.searchArchives(
            conn.client, conn.baseUrl,
            filter = query.filter,
            category = query.category,
            start = offset,
            sortby = query.sortby,
            order = query.order,
            newonly = query.newonly,
            untaggedonly = query.untaggedonly,
            groupbyTanks = fold,
            hideCompleted = hideCompleted(),
        )
        // Tank entries become display-only pseudo-Archives when folding is on,
        // and are dropped otherwise. The next offset advances by the raw
        // (pre-mapping) count so a dropped entry still consumes its slot.
        val items = result.toArchiveList(
            includeTanks = fold,
            tankProfileId = conn.profileId,
            tankBaseUrl = conn.baseUrl,
        )
        val rawCount = result.data.size
        // rawCount > 0 guards against a malformed empty page that still claims
        // more records — advancing by zero would re-request the same offset forever.
        val next = if (rawCount > 0 && offset + rawCount < result.recordsFiltered) offset + rawCount else null
        return Page(items, next)
    }
}
