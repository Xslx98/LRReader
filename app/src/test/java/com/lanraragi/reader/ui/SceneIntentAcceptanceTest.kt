package com.lanraragi.reader.ui

import com.lanraragi.reader.ui.scene.download.DownloadsScene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MainActivity is exported: a start_scene intent may come from any app.
 * Only the non-exported internal alias may open any registered scene.
 */
class SceneIntentAcceptanceTest {

    private val registered = setOf(DownloadsScene::class.java.name, "com.example.OtherScene")

    private fun accepted(component: String?, scene: String) =
        MainActivity.isSceneIntentAccepted(component, scene) { it in registered }

    @Test
    fun unregisteredClassNamesAreRejectedBeforeLoading() {
        assertFalse(accepted(MainActivity.INTERNAL_SCENE_ENTRY, "java.lang.String"))
        assertFalse(accepted(MainActivity::class.java.name, "java.lang.String"))
    }

    @Test
    fun theInternalAliasMayOpenAnyRegisteredScene() {
        assertTrue(accepted(MainActivity.INTERNAL_SCENE_ENTRY, "com.example.OtherScene"))
    }

    @Test
    fun theExportedEntryOnlyOpensTheAllowList() {
        assertTrue(accepted(MainActivity::class.java.name, DownloadsScene::class.java.name))
        assertFalse(accepted(MainActivity::class.java.name, "com.example.OtherScene"))
        assertFalse(accepted(null, "com.example.OtherScene"))
    }
}
