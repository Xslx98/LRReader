/*
 * Copyright 2026 The LRReader Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.lanraragi.reader.ui

import android.content.Context
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.appwidget.ContinueReadingWidget
import com.lanraragi.reader.download.TankMembershipSync
import com.lanraragi.reader.gallery.GalleryProvider2

/**
 * Production wiring for [TankMembershipSync]: Room repositories from the
 * [ServiceRegistry], the SP reading-progress store, the continue-reading
 * shortcut and widget. ViewModels hold a [Runner] seam so JVM tests swap
 * in a recorder; [runnerSafely] resolves the application lazily and
 * no-ops when it isn't up (plain-Application Robolectric tests) — the
 * same contract as [ContinueReadingShortcut.removeSafely].
 */
object TankMembershipSyncFactory {

    /** Seam the tank ViewModels call after a successful membership fetch. */
    fun interface Runner {
        suspend fun sync(profileId: Long, baseUrl: String, tanks: List<TankMembershipSync.TankTruth>)
    }

    fun runnerSafely(): Runner = Runner { profileId, baseUrl, tanks ->
        createSafely()?.sync(profileId, baseUrl, tanks)
    }

    fun createSafely(): TankMembershipSync? {
        val app = runCatching { LRReaderApplication.instance }.getOrNull() ?: return null
        return runCatching { create(app.applicationContext) }.getOrNull()
    }

    fun create(context: Context): TankMembershipSync {
        val data = ServiceRegistry.dataModule
        return TankMembershipSync(
            downloadDb = data.downloadDbRepository,
            history = data.historyRepository,
            progress = object : TankMembershipSync.ProgressStore {
                override fun load(arcid: String): Int = GalleryProvider2.loadReadingProgress(context, arcid)
                override fun save(arcid: String, page0: Int) =
                    GalleryProvider2.saveReadingProgress(context, arcid, page0)
            },
            shortcut = object : TankMembershipSync.ShortcutPort {
                override fun currentTarget(): Pair<String, Long>? = ContinueReadingShortcut.currentTarget(context)
                override suspend fun publish(arcid: String, profileId: Long) =
                    ContinueReadingShortcut.publish(context, data.historyRepository, arcid, profileId)
            },
            refreshWidget = { ContinueReadingWidget.refreshSafely() },
        )
    }
}
