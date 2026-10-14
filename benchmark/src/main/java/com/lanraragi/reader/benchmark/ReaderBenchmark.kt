package com.lanraragi.reader.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lanraragi.reader.benchmark.LrrScenario.launchToList
import com.lanraragi.reader.benchmark.LrrScenario.readPages
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Twenty page turns in the reader: decode, GL frame and tile-upload sections
 * (com.lanraragi.reader.util.TraceSections) plus peak memory. GLSurfaceView
 * frames are invisible to FrameTimingMetric, hence the glFrame section.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun readTwentyPages() {
        rule.measureRepeated(
            packageName = LrrScenario.PKG,
            metrics = listOf(
                TraceSectionMetric("LRR.reader.decode", TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("LRR.reader.decode", TraceSectionMetric.Mode.Max),
                TraceSectionMetric("LRR.reader.glFrame", TraceSectionMetric.Mode.Max),
                TraceSectionMetric("LRR.reader.upload", TraceSectionMetric.Mode.Sum),
                MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            ),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = { launchToList() },
        ) {
            readPages(PAGES)
        }
    }

    private companion object {
        const val ITERATIONS = 5
        const val PAGES = 20
    }
}
