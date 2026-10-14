package com.lanraragi.reader.gallery

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class DeferredProgressSaveTest {

    private val written = mutableListOf<Int>()
    private val save = DeferredProgressSave(delayMs = 1_000) { written += it }
    private val looper = shadowOf(Looper.getMainLooper())

    @Test
    fun aBurstOfPageTurnsIsWrittenOnceWithTheLastPage() {
        for (page in 1..20) {
            save.submit(page)
            looper.idleFor(Duration.ofMillis(40))
        }
        assertEquals(emptyList<Int>(), written)

        looper.idleFor(Duration.ofMillis(1_000))

        assertEquals(listOf(20), written)
    }

    @Test
    fun continuousTurningStillWritesEverySecond() {
        for (page in 1..30) {
            save.submit(page)
            looper.idleFor(Duration.ofMillis(100))
        }

        assertEquals(listOf(10, 20, 30), written)
    }

    @Test
    fun flushWritesThePendingPageAtOnceAndOnlyOnce() {
        save.submit(7)

        save.flush()
        looper.idleFor(Duration.ofMillis(2_000))
        save.flush()

        assertEquals(listOf(7), written)
    }
}
