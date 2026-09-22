package com.lanraragi.reader.appwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.lanraragi.reader.R
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.gallery.ReadingSessionEnd
import com.lanraragi.reader.gallery.ReadingSessionEvents
import com.lanraragi.reader.ui.ContinueReadingShortcut
import com.lanraragi.reader.ui.MainActivity
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.launch

/**
 * Renders the "Continue reading" home-screen widget (issue #9).
 *
 * Rides the same reading-session-end seam as [ContinueReadingShortcut] and
 * reuses its deep-link contract (action + extras -> MainActivity ->
 * buildReadIntent), so tapping the widget resumes exactly like tapping the
 * dynamic shortcut. Content comes from the per-profile history snapshot
 * (offline-safe); with no valid history row the widget shows an empty state
 * that just opens the app.
 *
 * Self-heal mirrors the shortcut's three removal paths (clear history /
 * delete profile / stale target), except the widget re-renders from the most
 * recent surviving history row instead of disappearing.
 */
object ContinueReadingWidget : ReadingSessionEvents.Listener {

    private const val TAG = "ContinueReadingWidget"
    private const val REQUEST_CONTINUE = 1
    private const val REQUEST_OPEN_APP = 2

    /** Register on the session-end seam. Call once from Application.onCreate. */
    fun install() {
        ReadingSessionEvents.register(this)
    }

    override fun onSessionEnd(end: ReadingSessionEnd) {
        val context = com.lanraragi.reader.LRReaderApplication.instance
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                update(
                    context,
                    ServiceRegistry.dataModule.historyRepository,
                    end.arcid,
                    end.serverProfileId,
                    endProgress1 = end.endPage + 1,
                )
            } catch (e: Exception) {
                Log.w(TAG, "continue-reading widget update failed")
            }
        }
    }

    /**
     * Event path: render the just-read archive from its history snapshot,
     * showing the page the session ended on ([endProgress1], 1-indexed)
     * rather than the snapshot's value, which may not be updated yet.
     * A missing snapshot (row evicted mid-flight) falls back to [refresh].
     */
    suspend fun update(
        context: Context,
        historyRepository: HistoryRepository,
        arcid: String,
        profileId: Long,
        endProgress1: Int = 0,
    ) {
        if (!hasWidgets(context)) return
        val archive = historyRepository.getArchiveSnapshot(arcid, profileId)
        if (archive != null) {
            val shown = if (endProgress1 > 0) archive.copy(progress = endProgress1) else archive
            push(context, buildViews(context, shown))
        } else {
            refresh(context, historyRepository, ServiceRegistry.dataModule.profileRepository)
        }
    }

    /**
     * Self-heal + launcher path: render the most recent history row across
     * all profiles, or the empty state when none survives.
     */
    suspend fun refresh(
        context: Context,
        historyRepository: HistoryRepository,
        profileRepository: ProfileRepository,
    ) {
        if (!hasWidgets(context)) return
        push(context, buildViews(context, latestArchive(historyRepository, profileRepository)))
    }

    /**
     * Most recent decodable history row whose source profile still exists,
     * or null. Profile deletion does not cascade ARCHIVE_LOCAL_STATE rows
     * (known ADR-003 leftover), so the filter keeps the widget from
     * resurrecting a dead-end row the deep link would only toast about.
     */
    @VisibleForTesting
    suspend fun latestArchive(
        historyRepository: HistoryRepository,
        profileRepository: ProfileRepository,
    ): Archive? {
        val liveProfiles = profileRepository.getAllProfiles().mapTo(HashSet()) { it.id }
        return historyRepository.getAllHistoryStatsRows()
            .filter { it.archive != null && it.serverProfileId in liveProfiles }
            .maxByOrNull { it.historyTime ?: Long.MIN_VALUE }
            ?.archive
    }

    /**
     * Fire-and-forget [refresh] for JVM-tested ViewModels: resolves the
     * application lazily and no-ops when it isn't up (plain-Application
     * Robolectric tests) — same contract as
     * [ContinueReadingShortcut.removeSafely].
     */
    fun refreshSafely() {
        val app = runCatching { com.lanraragi.reader.LRReaderApplication.instance }.getOrNull() ?: return
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                refresh(
                    app,
                    ServiceRegistry.dataModule.historyRepository,
                    ServiceRegistry.dataModule.profileRepository,
                )
            } catch (e: Exception) {
                Log.w(TAG, "continue-reading widget refresh failed")
            }
        }
    }

    /** Build the widget views: [archive] null renders the empty state. */
    @VisibleForTesting
    fun buildViews(context: Context, archive: Archive?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.appwidget_continue_reading)
        if (archive == null) {
            views.setViewVisibility(R.id.appwidget_title, View.GONE)
            views.setViewVisibility(R.id.appwidget_progress, View.GONE)
            views.setViewVisibility(R.id.appwidget_empty, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.appwidget_root,
                PendingIntent.getActivity(
                    context, REQUEST_OPEN_APP,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            // With an app lock set the home screen must not reveal what is
            // being read: generic label, no progress.
            val redact = SecuritySettings.isLockEnabled()
            val title = archive.title.takeUnless { redact || it.isBlank() }
                ?: context.getString(R.string.shortcut_continue_reading)
            views.setViewVisibility(R.id.appwidget_title, View.VISIBLE)
            views.setTextViewText(R.id.appwidget_title, title)
            val progress = if (redact) null else progressText(archive.progress, archive.pagecount)
            if (progress != null) {
                views.setViewVisibility(R.id.appwidget_progress, View.VISIBLE)
                views.setTextViewText(R.id.appwidget_progress, progress)
            } else {
                views.setViewVisibility(R.id.appwidget_progress, View.GONE)
            }
            views.setViewVisibility(R.id.appwidget_empty, View.GONE)
            views.setOnClickPendingIntent(
                R.id.appwidget_root,
                PendingIntent.getActivity(
                    context, REQUEST_CONTINUE,
                    continueIntent(context, archive.arcid, archive.serverProfileId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return views
    }

    /**
     * Deep-link intent — the exact contract [MainActivity.handleIntent]
     * already serves for the dynamic shortcut.
     */
    @VisibleForTesting
    fun continueIntent(context: Context, arcid: String, profileId: Long): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ContinueReadingShortcut.ACTION_CONTINUE_READING
            putExtra(ContinueReadingShortcut.KEY_ARCID, arcid)
            putExtra(ContinueReadingShortcut.KEY_PROFILE_ID, profileId)
        }

    /**
     * "12 / 100" locale-neutral progress line; hidden when the snapshot has
     * no usable page data. progress is the 1-based last-read page.
     */
    @VisibleForTesting
    fun progressText(progress: Int, pagecount: Int): String? {
        if (pagecount <= 0 || progress <= 0) return null
        return "${progress.coerceAtMost(pagecount)} / $pagecount"
    }

    private fun componentName(context: Context) =
        ComponentName(context, ContinueReadingWidgetProvider::class.java)

    private fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(componentName(context)).isNotEmpty()

    private fun push(context: Context, views: RemoteViews) {
        AppWidgetManager.getInstance(context).updateAppWidget(componentName(context), views)
    }
}
