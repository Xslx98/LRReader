package com.lanraragi.reader.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.ByteArrayInputStream

/** Audit 2026-10-04 C06 (R5): ANR / native-crash exits reach the local report store once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ExitInfoRecorderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("exit_test", Context.MODE_PRIVATE)
    private val store by lazy { CrashLogStore(tmp.root.resolve("crash")) }

    private fun rec(ts: Long, reason: Int, description: String? = "d", trace: () -> String? = { null }) =
        ExitInfoRecorder.ExitRecord(ts, reason, description, 100, 0, 1, 2, trace)

    @Test
    fun record_writesOnlyAnrNativeAndInitFailures_newerThanWatermark() {
        val recorder = ExitInfoRecorder(store, prefs)
        val written = recorder.record(
            listOf(
                rec(30, ExitInfoRecorder.REASON_ANR),
                rec(20, ExitInfoRecorder.REASON_CRASH_NATIVE),
                rec(25, ApplicationExitInfo.REASON_CRASH),
                rec(10, ApplicationExitInfo.REASON_USER_REQUESTED),
                rec(40, ExitInfoRecorder.REASON_INITIALIZATION_FAILURE),
            )
        )
        assertEquals(3, written)
        val reasons = store.list().map { it.readText().lineSequence().first { l -> l.startsWith("Reason=") } }
        assertEquals(setOf("Reason=ANR", "Reason=CRASH_NATIVE", "Reason=INITIALIZATION_FAILURE"), reasons.toSet())

        // Same history at the next boot: nothing new.
        assertEquals(0, recorder.record(listOf(rec(30, ExitInfoRecorder.REASON_ANR))))
        // A newer ANR is reported.
        assertEquals(1, recorder.record(listOf(rec(41, ExitInfoRecorder.REASON_ANR))))
    }

    @Test
    fun watermark_advancesPastUnreportedReasonsToo() {
        val recorder = ExitInfoRecorder(store, prefs)
        recorder.record(listOf(rec(50, ApplicationExitInfo.REASON_USER_REQUESTED)))
        assertEquals(0, recorder.record(listOf(rec(45, ExitInfoRecorder.REASON_ANR))))
    }

    @Test
    fun format_redactsDescriptionAndTrace_andSurvivesAThrowingTrace() {
        val recorder = ExitInfoRecorder(store, prefs)
        val text = recorder.format(
            rec(1, ExitInfoRecorder.REASON_ANR, "Input dispatching timed out http://192.168.1.2:3000/x") {
                "\"main\" waiting on 10.0.0.9"
            }
        )
        assertFalse(text, text.contains("192.168.1.2"))
        assertFalse(text, text.contains("10.0.0.9"))
        assertTrue(text, text.contains("======== Trace ========"))

        val noTrace = recorder.format(rec(1, ExitInfoRecorder.REASON_ANR) { error("binder died") })
        assertFalse(noTrace, noTrace.contains("======== Trace ========"))
    }

    @Test
    fun readCapped_truncatesLongTraces() {
        val text = ExitInfoRecorder.readCapped(ByteArrayInputStream(ByteArray(100) { 'a'.code.toByte() }), max = 10)
        assertTrue(text, text.startsWith("aaaaaaaaaa\n"))
        assertTrue(text, text.contains("truncated at 10 bytes"))
        assertEquals("abc", ExitInfoRecorder.readCapped(ByteArrayInputStream("abc".toByteArray()), max = 10))
    }

    @Test
    fun recordAtBoot_readsTheSystemExitHistory() {
        val am = context.getSystemService(ActivityManager::class.java)
        shadowOf(am).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(1234L)
                .setReason(ApplicationExitInfo.REASON_ANR)
                .setDescription("anr")
                .build()
        )
        assertEquals(1, ExitInfoRecorder.recordAtBoot(context, store, prefs))
        assertTrue(store.list().single().readText().contains("Reason=ANR"))
    }
}
