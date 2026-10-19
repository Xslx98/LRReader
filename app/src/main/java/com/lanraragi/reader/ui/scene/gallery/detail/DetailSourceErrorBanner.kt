package com.lanraragi.reader.ui.scene.gallery.detail

import android.content.Context
import android.view.View
import android.widget.TextView
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRHttpException
import com.lanraragi.reader.client.api.OrphanProfileException
import com.lanraragi.reader.client.api.ProfileLookupCache
import com.lanraragi.reader.client.api.friendlyError

/**
 * Source-server error banner (shown when the source LANraragi server is
 * unreachable / orphan / has deleted the archive but we still have
 * local-cache data to display).
 */
class DetailSourceErrorBanner(
    private val viewModel: GalleryDetailViewModel,
    private val context: () -> Context?,
    private val onRetry: () -> Unit,
    private val keepPageNormal: () -> Unit,
) {

    private var banner: View? = null
    private var text: TextView? = null
    private var retry: View? = null

    fun bind(belowHeader: View) {
        banner = belowHeader.findViewById(R.id.source_error_banner)
        text = belowHeader.findViewById(R.id.source_error_text)
        retry = belowHeader.findViewById(R.id.source_error_retry)
        retry?.setOnClickListener {
            banner?.visibility = View.GONE
            onRetry()
        }
    }

    fun hide() {
        banner?.visibility = View.GONE
    }

    /**
     * Show the cache-first error banner above the detail content. The
     * page is already rendered (LRU detail cache hit, or a degraded
     * detail seeded from the navigation Archive) so we keep the user
     * on STATE_NORMAL and surface the failure as a dismissible bar
     * with a Retry button. A subsequent successful load auto-hides it
     * via the [GalleryDetailViewModel.detailLoaded] observer.
     */
    fun show(e: Exception) {
        val ctx = context() ?: return
        val banner = banner ?: return
        val text = text ?: return
        text.text = tieredSourceErrorText(ctx, e)
        banner.visibility = View.VISIBLE
        // The banner is informational on top of cached data — make sure
        // the page itself stays in NORMAL so the cached content remains
        // interactive (cancel buttons, read button, etc.).
        keepPageNormal()
    }

    /**
     * Map a fetch failure to a tiered, user-actionable string. The four
     * tiers (orphan / unreachable / 404 / generic) match the cross-
     * server UX patterns established by Plex / email clients / RSS
     * readers — distinguishing why the source is unavailable lets the
     * user decide whether to retry, switch profiles, or give up.
     *
     * Profile name is looked up against the same [ProfileLookupCache]
     * the auth interceptor uses, so the banner shows the user-chosen
     * server label rather than a raw URL.
     */
    private fun tieredSourceErrorText(ctx: Context, e: Exception): String {
        val sid = viewModel.getEffectiveArchive()?.serverProfileId ?: 0L
        // Profile name comes from the same in-memory cache the auth
        // interceptor consults, so the banner mirrors what the user
        // sees in ServerListScene. If the lookup misses (orphan path
        // is handled below; this guards against the rare race where
        // the profile is deleted between request fire and response)
        // the placeholder collapses to empty rather than crashing the
        // resource formatter — the message still reads correctly.
        val profileName = ServiceRegistry.dataModule.profileLookupCache
            .findById(sid)?.name.orEmpty()
        return when {
            e is OrphanProfileException ->
                ctx.getString(R.string.lrr_detail_source_profile_orphan)
            // LANraragi returns HTTP 400 ("Invalid id") for unknown
            // arcids on /api/archives/{arcid}/metadata — semantically
            // equivalent to the archive having been deleted server-side.
            // 404 is also handled for forward-compat with Shinobu / future
            // servers that follow the more conventional REST mapping.
            e is LRRHttpException &&
                (e.code == 400 || e.code == 404) ->
                ctx.getString(R.string.lrr_detail_source_archive_deleted, profileName)
            e is java.net.UnknownHostException ||
                e is java.net.SocketTimeoutException ||
                e is java.net.ConnectException ->
                ctx.getString(R.string.lrr_detail_source_unreachable, profileName)
            else -> friendlyError(ctx, e)
        }
    }

    fun clear() {
        banner = null
        text = null
        retry = null
    }
}
