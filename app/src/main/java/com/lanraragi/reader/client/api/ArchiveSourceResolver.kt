package com.lanraragi.reader.client.api

import java.io.IOException

/**
 * Thrown by [resolveSourceBaseUrl] when the supplied server profile id
 * does not resolve to a known [com.hippo.ehviewer.dao.ServerProfile] —
 * i.e. the user deleted the source profile while a local reference to
 * the archive (download row, history entry, favorite) still exists.
 * Callers surface a user-facing message and stop.
 */
class OrphanProfileException(val profileId: Long) :
    IOException("Source profile id=$profileId no longer exists")

/**
 * Resolve the base URL of the LANraragi server that owns this archive,
 * given its server profile id as durably stored on
 * [com.hippo.ehviewer.dao.ArchiveLocalState] /
 * [com.hippo.ehviewer.dao.DownloadInfo].
 *
 * Routing rules:
 *  1. [serverProfileId] == 0 is treated as **legacy data**: archives
 *     written before the multi-profile schema landed never carry a
 *     profile id (and neither do downloads queued via the upgrade path
 *     of a single-profile install). Fall back to the active profile's
 *     URL via [LRRAuthManager.getServerUrl] so existing flows do not
 *     regress; null active URL → [OrphanProfileException].
 *  2. Any other id is resolved against [ProfileLookupCache]. Cache
 *     misses mean the user deleted the source profile — surface as
 *     [OrphanProfileException] so callers can show a localised tip
 *     instead of failing with a confusing HTTP error.
 *
 * Suspends until [ProfileLookupCache.awaitInitialized] completes, so
 * cold-start callers (download worker on app launch, detail VM started
 * before the Room flow has emitted) do not race the cache.
 */
suspend fun resolveSourceBaseUrl(
    serverProfileId: Long,
    cache: ProfileLookupCache,
): String {
    cache.awaitInitialized()
    if (serverProfileId == 0L) {
        return LRRAuthManager.getServerUrl()
            ?: throw OrphanProfileException(0L)
    }
    val profile = cache.findById(serverProfileId)
        ?: throw OrphanProfileException(serverProfileId)
    return profile.url
}
