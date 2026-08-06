package com.hippo.ehviewer.gallery

import android.content.Context
import com.hippo.ehviewer.ui.GalleryOpenHelper
import com.hippo.unifile.UniFile
import okhttp3.OkHttpClient

/**
 * Per-member source routing for the tank composite reader: a member with a
 * COMPLETE local download reads from disk, everything else streams. Same
 * completeness decision as [GalleryOpenHelper]'s standalone routing so the
 * two session kinds can never disagree about where a member's pages come
 * from.
 */
internal object TankMemberRouting {

    suspend fun resolve(
        context: Context,
        member: TankMemberSeed,
        profileId: Long,
        serverUrl: String,
        pageClient: OkHttpClient,
        listClient: OkHttpClient,
    ): TankMemberSource {
        val localDir = runCatching {
            GalleryOpenHelper.getLocalDownloadDir(context, member.toRoutingArchive(profileId))
        }.getOrNull()
        if (localDir != null &&
            GalleryOpenHelper.isLocalCopyComplete(localDir, member.pagecount)
        ) {
            val uniFile = UniFile.fromFile(localDir)
            if (uniFile != null) {
                return DirTankMemberSource(context, member.arcid, uniFile)
            }
        }
        return LrrTankMemberSource(context, member.arcid, serverUrl, pageClient, listClient)
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
