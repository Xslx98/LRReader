package com.lanraragi.reader.ui

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.appwidget.ContinueReadingWidget
import com.lanraragi.reader.client.api.friendlyError
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.gallery.TankSessionRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the reader for a continue-reading shortcut or widget deep link. */
object ContinueReadingLauncher {

    private const val TAG = "MainActivity"

    /**
     * Continue-reading deep link (issue #15): rebuild the Archive from its
     * history snapshot and route through the canonical read-intent path
     * (position restore + offline handling ride along for free). No snapshot
     * means the row is gone — stay on the main list.
     */
    fun open(
        activity: AppCompatActivity,
        arcid: String,
        profileId: Long,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        activity.lifecycleScope.launch {
            val archive = try {
                withContext(ioDispatcher) {
                    // Profile gone (deleted since publish) or history row gone:
                    // both mean the shortcut is stale — self-heal by removing
                    // it and stay on the main list with a toast (issue #16).
                    if (ServiceRegistry.dataModule.profileRepository.findById(profileId) == null) {
                        null
                    } else {
                        ServiceRegistry.dataModule.historyRepository
                            .getArchiveSnapshot(arcid, profileId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "continue-reading snapshot load failed", e)
                null
            }
            if (archive == null) {
                ContinueReadingShortcut.remove(activity)
                // The widget may point at the same stale target (it shares the
                // deep-link contract) — re-render it from surviving history.
                ContinueReadingWidget.refreshSafely()
                Toast.makeText(
                    activity, R.string.continue_reading_unavailable, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            // Tank history rows resume the whole-tank composite session (the
            // membership re-fetch needs the server; a transient failure keeps
            // the shortcut and just toasts).
            if (isTankoubonId(archive.arcid)) {
                try {
                    val tankIntent = withContext(ioDispatcher) {
                        TankSessionRouter.buildResumeIntent(
                            activity, archive.arcid, archive.serverProfileId
                        )
                    }
                    activity.startActivity(tankIntent)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(
                        activity,
                        friendlyError(activity, e),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            activity.startActivity(GalleryOpenHelper.buildReadIntent(activity, archive))
        }
    }
}
