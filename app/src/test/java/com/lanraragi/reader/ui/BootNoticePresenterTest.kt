package com.lanraragi.reader.ui

import android.app.Dialog
import android.content.DialogInterface
import android.database.sqlite.SQLiteException
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lanraragi.reader.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Audit 2026-10-06d STAB-02: the boot loader reports a database failure on a
 * background thread, usually after MainActivity's onCreate. The recovery
 * dialog used to read the slot once in onCreate and so almost never showed.
 * [BootNoticePresenter] observes it: the dialog must appear whether the error
 * arrives before or after the host started, once per error, once per host
 * across a recreate, and not while the lock screen is up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class BootNoticePresenterTest {

    private lateinit var controller: ActivityController<HostActivity>

    @Before
    fun setUp() {
        ShadowDialog.reset()
        bootError.value = null
        resetNotice.value = null
        seenStamps.clear()
        lockUp = false
        retries = 0
        resets = 0
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        bootError.value = null
        resetNotice.value = null
    }

    @Test(timeout = 10_000)
    fun errorArrivingAfterStart_showsTheDialog() {
        startHost()
        assertEquals(0, showingDialogs().size)

        bootError.value = SQLiteException("migration 30->31 failed")
        idle()

        val dialog = showingDialogs().single()
        val message = dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString()
        assertTrue(message, message.contains("migration 30->31 failed"))
    }

    @Test(timeout = 10_000)
    fun errorArrivingBeforeCreate_showsTheDialog() {
        bootError.value = SQLiteException("disk I/O error")
        startHost()

        assertEquals(1, showingDialogs().size)
    }

    @Test(timeout = 10_000)
    fun dialogIsShownOnce_andAnsweringClearsTheError() {
        bootError.value = SQLiteException("broken")
        startHost()
        controller.get().presenter.maybeShow()
        idle()
        assertEquals(1, ShadowDialog.getShownDialogs().size)

        (showingDialogs().single() as AlertDialog).getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        idle()
        assertNull(bootError.value)
        assertEquals(0, showingDialogs().size)

        controller.get().presenter.maybeShow()
        idle()
        assertEquals(1, ShadowDialog.getShownDialogs().size)
    }

    @Test(timeout = 10_000)
    fun recreate_showsTheUnansweredDialogOnceInTheNewActivity() {
        bootError.value = SQLiteException("broken")
        startHost()
        val first = showingDialogs().single()

        controller.recreate()
        idle()

        assertTrue("old activity's dialog must be dismissed", !first.isShowing)
        val now = showingDialogs()
        assertEquals(1, now.size)
        assertTrue(now.single() !== first)
    }

    @Test(timeout = 10_000)
    fun lockScreenUp_waitsUntilTheHostRechecks() {
        lockUp = true
        startHost()
        bootError.value = SQLiteException("broken")
        idle()
        assertEquals(0, showingDialogs().size)

        lockUp = false
        controller.get().presenter.maybeShow()
        idle()
        assertEquals(1, showingDialogs().size)
    }

    @Test(timeout = 10_000)
    fun retryAndReset_answerTheErrorAndCallTheHost() {
        bootError.value = SQLiteException("broken")
        startHost()
        (showingDialogs().single() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(1, retries)
        assertNull(bootError.value)

        bootError.value = SQLiteException("broken again")
        idle()
        (showingDialogs().single() as AlertDialog).getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(1, resets)
        assertNull(bootError.value)
    }

    @Test(timeout = 10_000)
    fun databaseResetNotice_arrivingAfterStart_isShownOnceAndMarkedSeenOnOk() {
        startHost()
        resetNotice.value = 1234L
        idle()

        val dialog = showingDialogs().single() as AlertDialog
        controller.get().presenter.maybeShow()
        idle()
        assertEquals(1, ShadowDialog.getShownDialogs().size)
        val message = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
        assertEquals(controller.get().getString(R.string.lrr_db_reset_notice_message), message)

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf(1234L), seenStamps)
        assertNull(resetNotice.value)
    }

    @Test(timeout = 10_000)
    fun databaseResetNotice_waitsForTheLockAndSurvivesRecreateUnanswered() {
        lockUp = true
        resetNotice.value = 99L
        startHost()
        assertEquals(0, showingDialogs().size)

        lockUp = false
        controller.get().presenter.maybeShow()
        idle()
        val first = showingDialogs().single()

        controller.recreate()
        idle()
        assertTrue(!first.isShowing)
        assertEquals(1, showingDialogs().size)
        assertTrue(seenStamps.isEmpty())
    }

    @Test(timeout = 10_000)
    fun databaseFromANewerVersion_explainsTheDowngrade() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("downgrade-dialog-test.db")
        file.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE t(x)")
            it.version = 999
        }
        val db = com.lanraragi.reader.dao.AppDatabase.build(context, file.name)
        // The open the boot loader's first query triggers.
        val failure = try {
            db.openHelper.writableDatabase
            null
        } catch (e: IllegalStateException) {
            e
        } finally {
            db.close()
        }
        file.parentFile!!.listFiles().orEmpty().filter { it.name.startsWith(file.name) }.forEach { it.delete() }

        bootError.value = failure
        startHost()

        val dialog = showingDialogs().single()
        val message = dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString()
        assertEquals(controller.get().getString(R.string.lrr_boot_db_newer_message), message)
    }

    private fun startHost() {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        idle()
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun showingDialogs(): List<Dialog> = ShadowDialog.getShownDialogs().filter { it.isShowing }

    /** Minimal host: what MainActivity wires, without its scene machinery. */
    class HostActivity : AppCompatActivity() {
        lateinit var presenter: BootNoticePresenter

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.AppTheme)
            super.onCreate(savedInstanceState)
            presenter = BootNoticePresenter(
                activity = this,
                isLockScreenUp = { lockUp },
                onRetry = { retries++ },
                onResetDatabase = { resets++ },
                onResetNoticeSeen = { seenStamps += it },
                bootError = bootError,
                resetNotice = resetNotice,
            ).also { it.install() }
        }
    }

    companion object {
        val bootError = MutableStateFlow<Throwable?>(null)
        val resetNotice = MutableStateFlow<Long?>(null)
        val seenStamps = mutableListOf<Long>()
        var lockUp = false
        var retries = 0
        var resets = 0
    }
}
