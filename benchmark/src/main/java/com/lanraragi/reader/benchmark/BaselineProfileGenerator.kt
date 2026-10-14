package com.lanraragi.reader.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lanraragi.reader.benchmark.LrrScenario.flingList
import com.lanraragi.reader.benchmark.LrrScenario.launchToList
import com.lanraragi.reader.benchmark.LrrScenario.readPages
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects the baseline (and startup) profile over the hot path: cold start
 * to the list, flings, detail page, reader. `./gradlew
 * :app:generateAppReleaseBaselineProfile` writes it to
 * app/src/main/generated/baselineProfiles/ — commit the result; CI checks it
 * is packaged (scripts/ci/check-baseline-profile.sh).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        // Few iterations on purpose: the managed emulator on the owner's
        // machine has died twice past the 10-minute mark (host memory), and
        // the hot path is deterministic enough that four passes converge.
        rule.collect(
            packageName = LrrScenario.PKG,
            maxIterations = MAX_ITERATIONS,
            stableIterations = STABLE_ITERATIONS,
            includeInStartupProfile = true,
        ) {
            pressHome()
            launchToList()
            flingList(FLINGS)
            readPages(PAGES)
        }
    }

    private companion object {
        const val FLINGS = 4
        const val PAGES = 10
        const val MAX_ITERATIONS = 4
        const val STABLE_ITERATIONS = 2
    }
}
