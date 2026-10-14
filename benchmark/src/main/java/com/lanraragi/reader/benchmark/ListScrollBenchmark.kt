package com.lanraragi.reader.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lanraragi.reader.benchmark.LrrScenario.flingList
import com.lanraragi.reader.benchmark.LrrScenario.launchToList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame timing (HWUI) and row-bind sections over six list flings. */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ListScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun flingList() {
        rule.measureRepeated(
            packageName = LrrScenario.PKG,
            metrics = listOf(
                FrameTimingMetric(),
                TraceSectionMetric("LRR.list.bind", TraceSectionMetric.Mode.Sum),
            ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = { launchToList() },
        ) {
            flingList(FLINGS)
        }
    }

    private companion object {
        const val ITERATIONS = 5
        const val FLINGS = 6
    }
}
