package com.lanraragi.reader.download

import com.lanraragi.reader.ui.MainActivity
import com.lanraragi.reader.ui.scene.download.DownloadsScene
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notification taps of [DownloadService] name their activity and scene as
 * strings, so the download engine does not import the UI layer (rule D6). A
 * rename on the UI side must show up here instead of as a dead notification.
 */
class DownloadServiceTargetsTest {

    @Test
    fun targetActivityIsTheInternalSceneEntry() {
        assertEquals(MainActivity.INTERNAL_SCENE_ENTRY, DownloadService.TARGET_ACTIVITY)
    }

    @Test
    fun targetSceneIsTheDownloadsScene() {
        assertEquals(DownloadsScene::class.java.name, DownloadService.TARGET_SCENE)
    }
}
