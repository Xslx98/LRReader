package com.hippo.ehviewer.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import com.hippo.ehviewer.ServiceRegistry
import kotlinx.coroutines.launch

/**
 * Receiver for the continue-reading widget (issue #9). All rendering lives
 * in [ContinueReadingWidget]; this class only answers launcher-driven
 * updates (widget placed, launcher restart, boot) by re-rendering from the
 * most recent history row. updatePeriodMillis is 0 — content changes ride
 * the reading-session-end seam instead of polling.
 */
class ContinueReadingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Room work must leave the broadcast main thread; goAsync keeps the
        // process alive until the render lands.
        val pendingResult = goAsync()
        ServiceRegistry.coroutineModule.ioScope.launch {
            try {
                ContinueReadingWidget.refresh(
                    context.applicationContext,
                    ServiceRegistry.dataModule.historyRepository,
                )
            } catch (e: Exception) {
                Log.w(TAG, "continue-reading widget onUpdate failed")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ContinueReadingWidgetProvider"
    }
}
