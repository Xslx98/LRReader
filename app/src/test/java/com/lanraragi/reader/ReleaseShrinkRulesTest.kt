package com.lanraragi.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit 2026-10-06d REL-04: R8 keeps line info by default and stamps every
 * class's source file as "r8-map-id-<pg_map_id>", so each frame of a release
 * crash report names the mapping.txt that retraces it. The two rules usually
 * recommended for retracing would replace that stamp ("SourceFile" or the
 * real file names). scripts/ci/check-dex-retrace.sh checks the built DEX;
 * this test catches the rules early. Reads the rules file the release build
 * uses (Gradle runs unit tests in the module dir).
 */
class ReleaseShrinkRulesTest {

    private fun rules(): List<String> {
        val file = File("proguard-rules.pro")
        assertTrue("run from the app module dir: ${file.absolutePath}", file.isFile)
        return file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun sourceFileStampIsNotReplaced() {
        val rules = rules()
        assertEquals(emptyList<String>(), rules.filter { it.startsWith("-renamesourcefileattribute") })
        val keptSourceFile = rules
            .filter { it.startsWith("-keepattributes") }
            .filter { line -> line.removePrefix("-keepattributes").split(',').any { it.trim() == "SourceFile" } }
        assertEquals(emptyList<String>(), keptSourceFile)
    }
}
