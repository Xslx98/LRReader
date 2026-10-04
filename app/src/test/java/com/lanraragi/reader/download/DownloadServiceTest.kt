package com.lanraragi.reader.download

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.Settings
import com.lanraragi.reader.awaitUntil
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.DownloadDbRepository
import com.lanraragi.reader.dao.HistoryRepository
import com.lanraragi.reader.module.CoroutineModule
import com.lanraragi.reader.module.IDataModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Service-level tests of [DownloadService]'s command handling (audit
 * 2026-10-04 C08/C09): a command that throws does not stall the queue, and
 * the service stops itself only with the startId of the last handled
 * command, so Android keeps it alive while a newer start is pending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadServiceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManager
    private var controller: ServiceController<DownloadService>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Settings.initialize(context)
        ServiceRegistry.initializeForTest(CoroutineModule())
        scope = containedTestScope()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val repo = DownloadDbRepository(db.archiveLocalStateDao(), db.downloadDao(), db, Dispatchers.Unconfined)
        ServiceRegistry.initializeForTest(
            data = object : IDataModule {
                override val searchHistoryRepository get() = unset()
                override val downloadDbRepository get() = repo
                override val downloadManager get() = manager
                override val favouriteStatusRouter get() = unset()
                override val historyRepository get() = HistoryRepository(db.archiveLocalStateDao(), db)
                override val profileRepository get() = unset()
                override val profileLookupCache get() = unset()
                override val quickSearchRepository get() = unset()
                override val favoritesRepository get() = unset()
                override val archiveDetailCache get() = unset()
                override val spiderInfoCache get() = unset()
                override fun clearArchiveDetailCache() {}
            }
        )
        ShadowLooper.idleMainLooper()
        manager = DownloadManager(context, scope)
        runBlocking { manager.awaitInitAsync() }
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        controller?.destroy()
        scope.cancel()
        ShadowLooper.idleMainLooper()
        db.close()
        // Leave the registry as an uninitialized one behaves: every member
        // throws an Exception (not an Error), which later tests in this
        // Robolectric sandbox rely on when they do not install their own.
        ServiceRegistry.initializeForTest(data = UnsetDataModule)
    }

    private object UnsetDataModule : IDataModule {
        override val searchHistoryRepository get() = unset()
        override val downloadDbRepository get() = unset()
        override val downloadManager get() = unset()
        override val favouriteStatusRouter get() = unset()
        override val historyRepository get() = unset()
        override val profileRepository get() = unset()
        override val profileLookupCache get() = unset()
        override val quickSearchRepository get() = unset()
        override val favoritesRepository get() = unset()
        override val archiveDetailCache get() = unset()
        override val spiderInfoCache get() = unset()
        override fun clearArchiveDetailCache() {}
    }

    private fun intent(action: String) = Intent(context, DownloadService::class.java).setAction(action)

    @Test
    fun throwingCommand_doesNotStallLaterCommands() {
        val c = Robolectric.buildService(DownloadService::class.java).create().also { controller = it }
        // An ACTION_START whose archive extra is not an Archive throws inside
        // handleIntent (ClassCastException). It used to end the command loop.
        val bad = intent(DownloadService.ACTION_START)
            .putExtra(DownloadService.KEY_ARCHIVE, Bundle())
        c.withIntent(bad).startCommand(0, 1)
        c.withIntent(intent(DownloadService.ACTION_STOP_ALL)).startCommand(0, 2)

        val shadow = shadowOf(c.get())
        awaitUntil(message = "service never stopped after the second command") {
            shadow.stopSelfResultId == 2
        }
        assertTrue(shadow.isStoppedBySelf)
    }

    @Test
    fun stopsOnlyWithTheLastHandledStartId() {
        val c = Robolectric.buildService(DownloadService::class.java).create().also { controller = it }
        c.withIntent(intent(DownloadService.ACTION_STOP_ALL)).startCommand(0, 1)
        c.withIntent(intent(DownloadService.ACTION_CLEAR)).startCommand(0, 2)
        c.withIntent(intent(DownloadService.ACTION_STOP_ALL)).startCommand(0, 3)

        val shadow = shadowOf(c.get())
        awaitUntil { shadow.stopSelfResultId == 3 }
        // A plain stopSelf() would destroy the service whatever is still queued.
        assertEquals(0, shadow.stopSelfId)
    }
}

/** Same failure an uninitialized [ServiceRegistry] gives: an Exception, which callers' catch blocks handle. */
private fun unset(): Nothing = throw IllegalStateException("ServiceRegistry.dataModule not set up in this test")
