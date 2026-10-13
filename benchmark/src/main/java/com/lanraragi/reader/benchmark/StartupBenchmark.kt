package com.lanraragi.reader.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lanraragi.reader.benchmark.LrrScenario.launchToList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold / warm start to the gallery list. TTFD comes from the list scene's
 * reportFullyDrawn (first page landed). `None` vs `Partial` shows what the
 * packaged baseline profile buys on a sideloaded install.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldNoCompilation() = startup(StartupMode.COLD, CompilationMode.None())

    @Test
    fun coldBaselineProfile() =
        startup(StartupMode.COLD, CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun warmBaselineProfile() =
        startup(StartupMode.WARM, CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(startupMode: StartupMode, compilationMode: CompilationMode) {
        rule.measureRepeated(
            packageName = LrrScenario.PKG,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = startupMode,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            launchToList()
        }
    }

    private companion object {
        const val ITERATIONS = 5
    }
}
