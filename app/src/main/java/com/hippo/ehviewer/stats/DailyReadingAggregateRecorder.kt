package com.hippo.ehviewer.stats

import android.util.Log
import androidx.room.withTransaction
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DailyReadingAggregate
import com.hippo.ehviewer.gallery.ReadingSessionEnd
import com.hippo.ehviewer.gallery.ReadingSessionEvents
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Accumulates the daily reading aggregate (issue #20) from the
 * reading-session-end seam: one row per (device-local day × profile), pages
 * delta = max(0, end − start) so backward jumps and re-reads never count,
 * completed increments only when the session CROSSED into completion
 * (re-opening an already-finished archive at its last page adds nothing).
 *
 * No UI consumes the table yet — it accumulates history for future trend
 * features. The repository stays off IDataModule until a UI consumer exists.
 */
object DailyReadingAggregateRecorder : ReadingSessionEvents.Listener {

    private const val TAG = "DailyReadingAggregate"

    /** Register on the session-end seam. Call once from Application.onCreate. */
    fun install() {
        ReadingSessionEvents.register(this)
    }

    override fun onSessionEnd(end: ReadingSessionEnd) {
        val app = runCatching { com.hippo.ehviewer.EhApplication.instance }.getOrNull() ?: return
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                record(AppDatabase.getInstance(app), end, LocalDate.now().toEpochDay())
            } catch (e: Exception) {
                Log.w(TAG, "daily aggregate write failed")
            }
        }
    }

    /** Testable core: apply one session end onto the (day × profile) row. */
    suspend fun record(db: AppDatabase, end: ReadingSessionEnd, epochDay: Long) {
        if (end.serverProfileId <= 0) return
        val pages = (end.endPage - end.startPage).coerceAtLeast(0).toLong()
        val lastPage = end.pageCount - 1
        // pageCount == 1 has no crossing (startPage can never be below page 0):
        // every session on a single-page archive counts as a completion —
        // re-open inflation limited to single-pagers is accepted (approximate
        // statistic by design; review finding on issue #20).
        val crossedIntoCompletion = end.pageCount > 0 &&
            (end.pageCount == 1 || end.startPage < lastPage) && end.endPage >= lastPage
        val completed = if (crossedIntoCompletion) 1 else 0

        val dao = db.statsDao()
        db.withTransaction {
            dao.insertDailyAggregateIfAbsent(
                DailyReadingAggregate(epochDay, end.serverProfileId, 0, 0)
            )
            dao.accumulateDailyAggregate(epochDay, end.serverProfileId, pages, completed)
        }
    }
}
