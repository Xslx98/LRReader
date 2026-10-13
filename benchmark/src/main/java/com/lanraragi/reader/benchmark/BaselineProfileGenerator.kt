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
        rule.collect(
            packageName = LrrScenario.PKG,
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
    }
}
