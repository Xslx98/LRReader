package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.HistoryRepository
import com.hippo.ehviewer.gallery.ReadingSessionEnd
import com.hippo.ehviewer.gallery.ReadingSessionEvents
import kotlinx.coroutines.launch

/**
 * Publishes the "Continue reading" dynamic launcher shortcut (issue #15).
 *
 * Consumes the reading-session-end seam: each session end re-publishes the
 * single [SHORTCUT_ID] shortcut pointing at the just-read archive — which is
 * by definition the most recently read one. The label is the archive title
 * (app icon, no thumbnail — triage decision); the intent deep-links
 * [MainActivity] with arcid + source profile id, which routes into the
 * existing read-intent path. With no history snapshot nothing is published,
 * so a fresh install never shows the shortcut.
 */
object ContinueReadingShortcut : ReadingSessionEvents.Listener {

    const val SHORTCUT_ID = "continue_reading"
    const val ACTION_CONTINUE_READING = "com.lanraragi.reader.action.CONTINUE_READING"
    const val KEY_ARCID = "continue_arcid"
    const val KEY_PROFILE_ID = "continue_profile_id"

    private const val TAG = "ContinueReadingShortcut"

    /** Register on the session-end seam. Call once from Application.onCreate. */
    fun install() {
        ReadingSessionEvents.register(this)
    }

    override fun onSessionEnd(end: ReadingSessionEnd) {
        val context = com.hippo.ehviewer.EhApplication.instance
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                publish(
                    context,
                    ServiceRegistry.dataModule.historyRepository,
                    end.arcid,
                    end.serverProfileId,
                )
            } catch (e: Exception) {
                Log.w(TAG, "continue-reading shortcut publish failed")
            }
        }
    }

    /**
     * Build + push the shortcut for [arcid]/[profileId] from its history
     * snapshot. No snapshot (no history row) -> no publish.
     */
    suspend fun publish(
        context: Context,
        historyRepository: HistoryRepository,
        arcid: String,
        profileId: Long,
    ) {
        val archive = historyRepository.getArchiveSnapshot(arcid, profileId) ?: return
        val title = archive.title.ifBlank { context.getString(R.string.shortcut_continue_reading) }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_CONTINUE_READING
            putExtra(KEY_ARCID, arcid)
            putExtra(KEY_PROFILE_ID, profileId)
        }
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(title)
            .setLongLabel(title)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        // pushDynamicShortcut replaces the same-id shortcut and respects the
        // launcher count limit + background rate limit.
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /** Remove the shortcut (history cleared / profile deleted — issue #16). */
    fun remove(context: Context) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(SHORTCUT_ID))
    }
}
