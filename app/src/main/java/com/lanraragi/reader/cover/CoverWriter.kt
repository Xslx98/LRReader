package com.lanraragi.reader.cover

import com.lanraragi.reader.client.api.LRRArchiveApi
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.event.AppEventBus
import com.lanraragi.reader.event.ArchiveCoverChangedEvent
import com.lanraragi.reader.server.ServerConnection
import com.lanraragi.reader.tankoubon.TankCoverChoiceStore

/**
 * Sets a cover on the server and moves the local state that depends on it.
 * Every local step runs only after the server accepted the write, so a failed
 * write (the call throws) leaves the old cover, its stamp and its choice alone.
 */
object CoverWriter {

    /** Makes the 1-indexed [page1] of [arcid] its cover, re-keys the image and tells covered screens. */
    suspend fun setArchiveCover(conn: ServerConnection, arcid: String, page1: Int) {
        LRRArchiveApi.updateThumbnail(conn.client, conn.baseUrl, arcid, page1 = page1)
        ArchiveCoverStamps.bump(arcid)
        AppEventBus.postArchiveCoverChangedEvent(ArchiveCoverChangedEvent(arcid))
    }

    /**
     * Makes the 1-indexed [globalPage1] of [tankId] its cover and re-keys the
     * image. [chosenMember] answers the (arcid, 0-indexed page) behind that
     * page, or null when unknown; it is remembered in [choices] so the app's
     * own reorders can put the cover back.
     */
    suspend fun setTankCover(
        conn: ServerConnection,
        tankId: String,
        globalPage1: Int,
        choices: TankCoverChoiceStore = TankCoverChoiceStore.default,
        chosenMember: () -> Pair<String, Int>?,
    ) {
        LRRTankoubonApi.updateTankThumbnail(conn.client, conn.baseUrl, tankId, globalPage1 = globalPage1)
        TankCoverCacheStamp.bump(tankId)
        chosenMember()?.let { (arcid, page0) ->
            choices.put(tankId, TankCoverChoiceStore.Choice(arcid, page0, conn.profileId))
        }
    }
}
