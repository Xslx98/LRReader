package com.lanraragi.reader.gallery

import android.content.Context
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.framework.unifile.UniFile
import okhttp3.OkHttpClient

/**
 * Per-member source routing for the tank composite reader — the standalone
 * reader's rule ([com.lanraragi.reader.ui.GalleryOpenHelper]) applied per
 * member, through the shared [DownloadDirResolver] so the two session
 * kinds can never disagree about where a member's pages come from:
 *
 *  - complete local copy → [DirTankMemberSource];
 *  - local pages (or a tracked download's pending dir) with network up →
 *    [LrrTankMemberSource] in hybrid mode, reading and filling that dir;
 *  - local pages offline → [DirTankMemberSource] over the partial dir
 *    (missing pages surface as the reader's per-page error);
 *  - nothing local → plain streaming.
 */
internal object TankMemberRouting {

    @Suppress("LongParameterList")
    suspend fun resolve(
        context: Context,
        member: TankMemberSeed,
        profileId: Long,
        serverUrl: String,
        pageClient: OkHttpClient,
        listClient: OkHttpClient,
        resolver: DownloadDirResolver = DownloadDirResolver,
        networkAvailable: () -> Boolean = { ServiceRegistry.networkModule.networkMonitor.isAvailable },
    ): TankMemberSource {
        val archive = member.toRoutingArchive(profileId)
        val localDir = runCatching { resolver.localDownloadDir(context, archive) }.getOrNull()
        if (localDir != null) {
            val complete = resolver.isLocalCopyComplete(localDir, member.pagecount)
            if (complete || !networkAvailable()) {
                UniFile.fromFile(localDir)?.let { uniFile ->
                    return DirTankMemberSource(context, member.arcid, uniFile)
                }
            }
        }
        val hybridDir = localDir ?: runCatching { resolver.pendingDownloadDir(archive) }.getOrNull()
        val store = hybridDir?.let {
            HybridPageStore(it, ReaderPageCache.getCacheDir(context, member.arcid))
        }
        return LrrTankMemberSource(context, member.arcid, serverUrl, pageClient, listClient, store)
    }
}

/** Minimal [com.lanraragi.reader.domain.Archive] carrying just what dir resolution reads. */
private fun TankMemberSeed.toRoutingArchive(profileId: Long) =
    com.lanraragi.reader.domain.Archive(
        arcid = arcid,
        title = title,
        tags = emptyMap(),
        pagecount = pagecount,
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = "",
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = profileId,
    )
