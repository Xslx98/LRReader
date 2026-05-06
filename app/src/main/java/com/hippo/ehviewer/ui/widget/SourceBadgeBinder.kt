package com.hippo.ehviewer.ui.widget

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
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

    // Square badge drawable shared across rows; mutate() so each
    // ViewHolder's tint state is independent — without it,
    // RecyclerView recycling would let one ViewHolder's tint bleed
    // into another binding the same drawable instance.
    val bg = AppCompatResources.getDrawable(context, R.drawable.lrr_server_badge_bg)
        ?.mutate()
    if (bg != null) {
        DrawableCompat.setTint(bg, bgColor)
        badge.background = bg
    }
    badge.setTextColor(fgColor)
    badge.visibility = View.VISIBLE
    return resolvedName
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
