package com.hippo.ehviewer.gallery

import android.content.Context
import android.content.Intent
import com.hippo.ehviewer.ui.GalleryOpenHelper

/**
 * Latest full [TankSessionSeed] per published tank context. The
 * [ReadingContext.Tankoubon] the tank detail scene publishes carries ids +
 * offsets but not member titles/pagecounts, which a composite session seed
 * needs — the scene deposits the full seed here alongside the context so
 * the member detail page's READ entries can rebuild the whole-tank
 * session (spec entry point 2). Process-scoped, latest-wins: only one
 * tank detail flow is interactively relevant at a time.
 */
object TankSeedStore {

    @Volatile
    private var latest: TankSessionSeed? = null

    fun publish(seed: TankSessionSeed) {
        latest = seed
    }

    fun seedFor(tankId: String): TankSessionSeed? =
        latest?.takeIf { it.tankId == tankId }
}

/**
 * Routes a member-detail READ into the whole-tank composite session when
 * the member was reached through a tank detail flow (published
 * [ReadingContext.Tankoubon] + deposited seed). Null = no tank context;
 * the caller falls back to the standalone per-archive reader.
 */
object TankSessionRouter {

    /**
     * @param memberPage0 explicit member-local 0-indexed page (thumbnail
     *   tap), mapped to its global page; -1 = READ button semantics —
     *   resume the tank's saved global progress when it already sits
     *   inside this member, else open the member's first page.
     */
    fun tankIntentFor(context: Context, arcid: String, memberPage0: Int = -1): Intent? {
        val readingCtx =
            ReadingContextStore.currentFor(arcid) as? ReadingContext.Tankoubon ?: return null
        val seed = TankSeedStore.seedFor(readingCtx.tankId) ?: return null
        val memberIndex = seed.members.indexOfFirst { it.arcid == arcid }
        if (memberIndex < 0) return null

        val map = TankPageMap(seed.members.map { it.pagecount })
        if (map.total <= 0) return null
        val memberStart = map.memberStart(memberIndex)

        val startGlobalPage = if (memberPage0 >= 0) {
            memberStart + memberPage0
        } else {
            val savedGlobal = GalleryProvider2.loadReadingProgress(context, seed.tankId)
            val savedMember = map.locate(savedGlobal)?.first
            // Saved tank progress inside this member → let the provider
            // restore it (-1); anywhere else → this member's first page.
            if (savedMember == memberIndex) -1 else memberStart
        }
        return GalleryOpenHelper.buildTankReadIntent(context, seed, startGlobalPage)
    }
}
