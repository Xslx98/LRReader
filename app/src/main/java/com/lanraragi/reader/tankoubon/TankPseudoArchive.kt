package com.lanraragi.reader.tankoubon

import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.domain.Archive

/**
 * Builds the pseudo-[Archive] the gallery list inserts locally as a
 * PROVISIONAL tank row after "add to tankoubon" when the tank's real row is
 * not in the loaded data (spec 2026-09-22 §4 case 3). Shaped like
 * [com.lanraragi.reader.client.api.data.LRRArchive.toTankArchive] — real
 * `TANK_` arcid, tank name as title, blank extension/filename — so the
 * adapter renders it as any folded tank row and the next real page load
 * dedups it by arcid (no marker field needed).
 */
object TankPseudoArchive {

    /**
     * @param wasEmpty the tank had no members before this batch (as the
     *   picker saw it)
     * @param firstAddedThumbnailUrl cover of the first member the batch
     *   appended, if that archive is loaded. The server seeds a
     *   previously-empty tank with exactly that cover, so showing it now is
     *   what the next load shows. A tank that already had members keeps its
     *   own server cover via the tank thumbnail route instead — its cover is
     *   member #1, not the archive we just added.
     */
    @Suppress("LongParameterList")
    fun provisional(
        tankId: String,
        tankName: String,
        wasEmpty: Boolean,
        firstAddedThumbnailUrl: String?,
        sourceProfileId: Long,
        sourceBaseUrl: String,
    ): Archive = Archive(
        arcid = tankId,
        title = tankName,
        tags = emptyMap(),
        pagecount = 0,
        progress = 0,
        extension = "",
        filename = "",
        thumbnailUrl = if (wasEmpty && !firstAddedThumbnailUrl.isNullOrEmpty()) {
            firstAddedThumbnailUrl
        } else {
            LRRTankoubonApi.getTankoubonThumbnailUrl(sourceBaseUrl, tankId)
        },
        rating = 0f,
        isnew = false,
        lastreadtime = 0L,
        summary = null,
        serverProfileId = sourceProfileId,
    )
}
