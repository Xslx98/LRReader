package com.hippo.ehviewer.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.SparseArray
import android.view.View
import android.widget.TextView
import com.hippo.android.resource.AttrResources
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager

/**
 * Bind a source-server origin badge onto a [TextView].
 *
 * Shared by the download list (every row carries one) and the detail
 * header (visible only when the archive's source profile differs from
 * the active profile, so the user understands why a cross-server item
 * may behave differently). Both surfaces use the same drawable
 * ([R.drawable.lrr_server_badge_bg]) and the same per-profile colour
 * pairs (defined in [SERVER_BADGE_ATTR_PAIRS]) so a profile chosen by
 * the user keeps the same visual identity wherever it appears.
 *
 * Resolution goes through
 * [com.lanraragi.reader.client.api.ProfileLookupCache], the same in-
 * memory snapshot the auth interceptor consults — profile rename or
 * delete is reflected on the next bind without an explicit refresh.
 *
 * @param badge target view; visibility is set to [View.VISIBLE] on
 *   success.
 * @param serverProfileId archive's server profile id (0 = legacy row
 *   that pre-dates the multi-profile schema; falls back to the active
 *   profile, mirroring the download-list legacy attribution).
 * @return the resolved profile name (or null when none could be
 *   attributed) so callers that need to compare against the active
 *   profile name don't have to repeat the cache lookup.
 */
fun bindSourceServerBadge(badge: TextView, serverProfileId: Long): String? {
    val context: Context = badge.context

    // Legacy rows: best-effort attribute to the current active profile.
    // If active profile is also unset (degenerate fresh-install state)
    // effectiveId stays 0 and the cache lookup falls through to orphan.
    val effectiveId = if (serverProfileId == 0L) {
        LRRAuthManager.getActiveProfileId() ?: 0L
    } else {
        serverProfileId
    }

    val cache = ServiceRegistry.dataModule.profileLookupCache
    val profile = cache.findById(effectiveId)
    val bgColor: Int
    val fgColor: Int
    val resolvedName: String?
    if (profile == null) {
        badge.text = context.getString(R.string.lrr_download_source_deleted)
        bgColor = AttrResources.getAttrColor(context, androidx.appcompat.R.attr.colorError)
        fgColor = Color.WHITE
        resolvedName = null
    } else {
        badge.text = profile.name
        val (bgAttr, fgAttr) = attrPairForProfile(profile.id)
        bgColor = AttrResources.getAttrColor(context, bgAttr)
        fgColor = AttrResources.getAttrColor(context, fgAttr)
        resolvedName = profile.name
    }

    // Set the shape once per view; recycled holders keep their instance.
    // Colour goes through view-level backgroundTintList — the View
    // mutates its own background before applying a tint, so per-holder
    // tint isolation holds without inflating + mutate()-copying a fresh
    // drawable on every bind (the old full-bind allocation churn).
    if (badge.background == null) {
        badge.setBackgroundResource(R.drawable.lrr_server_badge_bg)
    }
    badge.backgroundTintList = badgeTintFor(bgColor)
    badge.setTextColor(fgColor)
    badge.visibility = View.VISIBLE
    return resolvedName
}

/**
 * ColorStateList instances are immutable — cache one per colour int
 * (8 palette slots + error, more only across theme changes) instead of
 * allocating ColorStateList.valueOf per bind. Main-thread only, like
 * every caller of [bindSourceServerBadge].
 */
private val badgeTintCache = SparseArray<ColorStateList>()

private fun badgeTintFor(color: Int): ColorStateList {
    badgeTintCache.get(color)?.let { return it }
    return ColorStateList.valueOf(color).also { badgeTintCache.put(color, it) }
}

/**
 * Map a server profile id to one of the [SERVER_BADGE_ATTR_PAIRS]
 * (bg-attr, fg-attr) tuples. id is the Room autoGenerate primary key
 * (Long, starts at 1, never changes), so the modulo result is stable
 * across renames and survives on-device data unchanged.
 *
 * Wraparound (>8 profiles) is acceptable: practical setups have 1-3
 * profiles, and even at 9+ the duplicate slot is far better UX than
 * the prior "everything green" baseline.
 */
private fun attrPairForProfile(profileId: Long): Pair<Int, Int> {
    val idx = ((profileId - 1L).rem(SERVER_BADGE_ATTR_PAIRS.size).toInt() +
        SERVER_BADGE_ATTR_PAIRS.size) % SERVER_BADGE_ATTR_PAIRS.size
    return SERVER_BADGE_ATTR_PAIRS[idx]
}

/**
 * Eight (background, foreground) attribute pairs. Each base theme
 * (Light / Dark / Black) supplies its own variant — see
 * attrs.xml + themes.xml + colors.xml. Indexed by
 * `(profileId - 1) % size` in [attrPairForProfile].
 */
private val SERVER_BADGE_ATTR_PAIRS = arrayOf(
    R.attr.serverBadge0Bg to R.attr.serverBadge0Fg,
    R.attr.serverBadge1Bg to R.attr.serverBadge1Fg,
    R.attr.serverBadge2Bg to R.attr.serverBadge2Fg,
    R.attr.serverBadge3Bg to R.attr.serverBadge3Fg,
    R.attr.serverBadge4Bg to R.attr.serverBadge4Fg,
    R.attr.serverBadge5Bg to R.attr.serverBadge5Fg,
    R.attr.serverBadge6Bg to R.attr.serverBadge6Fg,
    R.attr.serverBadge7Bg to R.attr.serverBadge7Fg,
)
