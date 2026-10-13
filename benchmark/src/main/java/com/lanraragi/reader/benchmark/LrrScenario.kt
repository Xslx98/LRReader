package com.lanraragi.reader.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * The scenario every benchmark and the profile generator share. The app is
 * launched with the benchmark-only seam extra so a fresh install lands on the
 * list (PerfSeam / BuildConfig.PERF_SEAM); the mock LANraragi must be
 * reachable from the device at [SERVER] (`adb reverse tcp:3939 tcp:3939`).
 */
object LrrScenario {
    const val PKG = "com.lanraragi.reader"

    /**
     * Mock server as seen from the device: loopback through `adb reverse` on
     * a connected device; a Gradle managed device (profile generation) has no
     * reverse, so pass `-Pandroid.testInstrumentationRunnerArguments.lrr.server=http://10.0.2.2:3939`.
     */
    val SERVER: String =
        InstrumentationRegistry.getArguments().getString("lrr.server") ?: "http://127.0.0.1:3939"
    private const val LIST_TIMEOUT_MS = 60_000L
    private const val UI_TIMEOUT_MS = 30_000L
    private const val PAGE_TURN_SETTLE_MS = 600L

    /** Launcher start; waits for the first gallery card (list page landed). */
    fun MacrobenchmarkScope.launchToList() {
        // The first launch asks for POST_NOTIFICATIONS; the system dialog
        // would hide the list from UiAutomator and skew the timing.
        device.executeShellCommand("pm grant $PKG android.permission.POST_NOTIFICATIONS")
        startActivityAndWait { intent -> intent.putExtra("lrr.perf.server", SERVER) }
        check(device.wait(Until.hasObject(By.res(PKG, "thumb_new")), LIST_TIMEOUT_MS)) {
            "gallery list did not appear (is the mock LANraragi reachable at $SERVER?)"
        }
    }

    /** Alternating down/up flings over the list. */
    fun MacrobenchmarkScope.flingList(times: Int) {
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(times) { i ->
            if (i % 2 == 0) device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 8)
            else device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 8)
            device.waitForIdle()
        }
    }

    /** Opens the 40-page mock archive, turns [pages] pages, returns to the list. */
    fun MacrobenchmarkScope.readPages(pages: Int) {
        val card = device.wait(Until.findObject(By.text("Mock Big Archive 1")), UI_TIMEOUT_MS)
            ?: device.findObject(By.res(PKG, "thumb_new"))
        card.click()
        device.wait(Until.findObject(By.res(PKG, "read")), UI_TIMEOUT_MS).click()
        Thread.sleep(PAGE_TURN_SETTLE_MS * 4)
        // GL surface: tap the left edge, the "next" side of the default
        // right-to-left direction.
        val x = device.displayWidth / 10
        val y = device.displayHeight / 2
        repeat(pages) {
            device.click(x, y)
            Thread.sleep(PAGE_TURN_SETTLE_MS)
        }
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.res(PKG, "thumb_new")), UI_TIMEOUT_MS)
    }
}
