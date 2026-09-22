package com.lanraragi.reader.gallery

import android.content.Context
import androidx.core.content.edit

/**
 * Where a tank session starts, reconciling the device's saved global page
 * with the server's tank progress.
 *
 * The server's value wins whenever it is known (> 0): every page turned on
 * this device is PUT right away, so a difference normally means the tank was
 * read elsewhere (another device, the web reader). The one case where the
 * local page is newer is a PUT that failed (offline reading); it is tracked
 * as "pending" until a later PUT of that page succeeds, and wins meanwhile.
 * With no server value the local page is used. The server has no progress
 * timestamp, so no finer ordering is possible.
 */
object TankProgress {

    private const val PREFS = "tank_progress_pending"

    /**
     * Pure decision. [localPage0] is the saved 0-indexed page (<= 0: none),
     * [pendingPage0] the unsynced local page (< 0: none), [serverProgress1]
     * the server's 1-indexed progress (<= 0: unknown).
     */
    fun resolveStart0(localPage0: Int, pendingPage0: Int, serverProgress1: Int): Int = when {
        pendingPage0 >= 0 -> pendingPage0
        serverProgress1 > 0 -> serverProgress1 - 1
        else -> localPage0.coerceAtLeast(0)
    }

    /** The start page for [tankId] given the server's [serverProgress1]. */
    fun start0(context: Context, tankId: String, serverProgress1: Int): Int = resolveStart0(
        GalleryProvider2.loadReadingProgress(context, tankId),
        pendingPage0(context, tankId),
        serverProgress1,
    )

    /** The page saved locally but not yet accepted by the server, or -1. */
    fun pendingPage0(context: Context, tankId: String): Int =
        prefs(context).getInt(tankId, -1)

    /** Record [page0] as saved locally, awaiting its server PUT. */
    fun markPending(context: Context, tankId: String, page0: Int) {
        prefs(context).edit { putInt(tankId, page0) }
    }

    /** The PUT of [page0] succeeded: clear the marker unless a newer page is pending. */
    fun markSynced(context: Context, tankId: String, page0: Int) {
        val prefs = prefs(context)
        if (prefs.getInt(tankId, -1) == page0) prefs.edit { remove(tankId) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
