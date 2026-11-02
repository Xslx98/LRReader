package com.lanraragi.reader.download

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.dao.DownloadState
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.download.DownloadRequests.Feedback
import com.lanraragi.reader.download.DownloadRequests.LabelDecision
import com.lanraragi.reader.download.DownloadRequests.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browse download entry: which archives are added, re-queued or skipped
 * as already local, which label new ones get, and the feedback the caller
 * shows. The service intents are captured instead of sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class DownloadRequestsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<Intent>()

    private fun archive(arcid: String) = Archive(
        arcid, "T $arcid", emptyMap(), 3, 0, "zip", "$arcid.zip", "", 0f, false, 0L, null, 1L,
    )

    private fun enqueue(
        states: Map<String, DownloadState>,
        decision: LabelDecision = LabelDecision.Use(null),
        labels: List<String?> = emptyList(),
    ): Outcome = DownloadRequests.enqueue(
        archives = states.keys.map(::archive),
        stateOf = { states.getValue(it) },
        labelDecision = { decision },
        labels = { labels },
        send = { sent += it },
        context = context,
    )

    @Test
    fun `plan splits by entry gate and keeps order`() {
        val states = mapOf(
            "new1" to DownloadState.INVALID,
            "failed" to DownloadState.FAILED,
            "done" to DownloadState.FINISH,
            "new2" to DownloadState.INVALID,
            "busy" to DownloadState.DOWNLOAD,
        )
        val plan = DownloadRequests.plan(states.keys.map(::archive)) { states.getValue(it) }
        assertEquals(listOf("new1", "new2"), plan.toAdd.map { it.arcid })
        assertEquals(listOf("failed", "busy"), plan.toRestart)
        assertEquals(listOf("done"), plan.alreadyLocal)
    }

    @Test
    fun `restarts go out as one range and new rows one start each with the label`() {
        val outcome = enqueue(
            mapOf(
                "a" to DownloadState.INVALID,
                "b" to DownloadState.NONE,
                "c" to DownloadState.INVALID,
                "d" to DownloadState.FAILED,
            ),
            decision = LabelDecision.Use("comics"),
        )

        assertEquals(Outcome.Done(Feedback.ADDED), outcome)
        assertEquals(
            listOf(DownloadService.ACTION_START_RANGE, DownloadService.ACTION_START, DownloadService.ACTION_START),
            sent.map { it.action },
        )
        assertEquals(arrayListOf("b", "d"), sent[0].getStringArrayListExtra(DownloadService.KEY_ARCID_LIST))
        assertEquals(listOf("a", "c"), sent.drop(1).map { it.getParcelableExtra<Archive>(DownloadService.KEY_ARCHIVE)!!.arcid })
        assertTrue(sent.drop(1).all { it.getStringExtra(DownloadService.KEY_LABEL) == "comics" })
    }

    @Test
    fun `only finished rows give already-local feedback and send nothing`() {
        val outcome = enqueue(mapOf("a" to DownloadState.FINISH))
        assertEquals(Outcome.Done(Feedback.ALREADY_LOCAL), outcome)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a restart beside a finished row is added feedback`() {
        val outcome = enqueue(mapOf("a" to DownloadState.FINISH, "b" to DownloadState.WAIT))
        assertEquals(Outcome.Done(Feedback.ADDED), outcome)
        assertEquals(listOf(DownloadService.ACTION_START_RANGE), sent.map { it.action })
    }

    @Test
    fun `new rows wait for a picked label while restarts are sent right away`() {
        val outcome = enqueue(
            mapOf("a" to DownloadState.INVALID, "b" to DownloadState.FAILED),
            decision = LabelDecision.Ask,
            labels = listOf("x", "y"),
        )
        if (outcome !is Outcome.PickLabel) fail("expected PickLabel, got $outcome") else {
            assertEquals(listOf("a"), outcome.toAdd.map { it.arcid })
            assertEquals(listOf("x", "y"), outcome.labels)
        }
        assertEquals(listOf(DownloadService.ACTION_START_RANGE), sent.map { it.action })
    }

    @Test
    fun `label decision`() {
        fun decide(force: Boolean, hasDefault: Boolean, default: String?, known: Set<String>, count: Int) =
            DownloadRequests.decideLabel(force, hasDefault, default, { it in known }, count)

        // Forced default wins over everything.
        assertEquals(LabelDecision.Use(null), decide(true, true, "x", setOf("x"), 2))
        // A remembered label is used while it exists; a remembered default label always.
        assertEquals(LabelDecision.Use("x"), decide(false, true, "x", setOf("x"), 2))
        assertEquals(LabelDecision.Use(null), decide(false, true, null, emptySet(), 2))
        // A remembered label that was deleted: ask, or the default label when none is left.
        assertEquals(LabelDecision.Ask, decide(false, true, "gone", setOf("x"), 1))
        assertEquals(LabelDecision.Use(null), decide(false, true, "gone", emptySet(), 0))
        // Nothing remembered: ask only when there is something to pick.
        assertEquals(LabelDecision.Ask, decide(false, false, null, setOf("x"), 1))
        assertEquals(LabelDecision.Use(null), decide(false, false, null, emptySet(), 0))
    }
}
