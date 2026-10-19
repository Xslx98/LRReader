package com.lanraragi.reader.ui

import com.lanraragi.framework.scene.SceneFactory
import com.lanraragi.framework.scene.SceneFragment
import com.lanraragi.framework.scene.StageActivity
import com.lanraragi.reader.ui.scene.LRRCategoriesScene
import com.lanraragi.reader.ui.scene.SecurityScene
import com.lanraragi.reader.ui.scene.ServerConfigScene
import com.lanraragi.reader.ui.scene.ServerListScene
import com.lanraragi.reader.ui.scene.TankoubonDetailScene
import com.lanraragi.reader.ui.scene.TankoubonsScene
import com.lanraragi.reader.ui.scene.download.DownloadLabelsScene
import com.lanraragi.reader.ui.scene.download.DownloadsScene
import com.lanraragi.reader.ui.scene.gallery.detail.GalleryDetailScene
import com.lanraragi.reader.ui.scene.gallery.list.GalleryListScene
import com.lanraragi.reader.ui.scene.gallery.list.QuickSearchScene
import com.lanraragi.reader.ui.scene.history.HistoryScene
import com.lanraragi.reader.ui.scene.stats.ReadingStatsScene
import com.lanraragi.reader.ui.scene.tankdetail.TankDetailScene

/** The scenes MainActivity hosts: their launch modes and factories. */
object MainScenes {

    /** Called once from MainActivity's companion initializer. */
    fun register() {
        StageActivity.registerLaunchMode(SecurityScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)

        StageActivity.registerLaunchMode(ServerConfigScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
        StageActivity.registerLaunchMode(ServerListScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
        StageActivity.registerLaunchMode(GalleryListScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
        StageActivity.registerLaunchMode(QuickSearchScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
        StageActivity.registerLaunchMode(GalleryDetailScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)

        StageActivity.registerLaunchMode(DownloadsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
        StageActivity.registerLaunchMode(DownloadLabelsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
        StageActivity.registerLaunchMode(LRRCategoriesScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
        StageActivity.registerLaunchMode(TankoubonsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)
        StageActivity.registerLaunchMode(TankoubonDetailScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
        StageActivity.registerLaunchMode(TankDetailScene::class.java, SceneFragment.LAUNCH_MODE_STANDARD)
        StageActivity.registerLaunchMode(HistoryScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TOP)
        StageActivity.registerLaunchMode(ReadingStatsScene::class.java, SceneFragment.LAUNCH_MODE_SINGLE_TASK)

        // Scene factory registrations (replaces reflection-based newInstance())
        SceneFactory.register(SecurityScene::class.java.name) { SecurityScene() }
        SceneFactory.register(ServerConfigScene::class.java.name) { ServerConfigScene() }
        SceneFactory.register(ServerListScene::class.java.name) { ServerListScene() }
        SceneFactory.register(GalleryListScene::class.java.name) { GalleryListScene() }
        SceneFactory.register(QuickSearchScene::class.java.name) { QuickSearchScene() }
        SceneFactory.register(GalleryDetailScene::class.java.name) { GalleryDetailScene() }
        SceneFactory.register(DownloadsScene::class.java.name) { DownloadsScene() }
        SceneFactory.register(DownloadLabelsScene::class.java.name) { DownloadLabelsScene() }
        SceneFactory.register(LRRCategoriesScene::class.java.name) { LRRCategoriesScene() }
        SceneFactory.register(TankoubonsScene::class.java.name) { TankoubonsScene() }
        SceneFactory.register(TankoubonDetailScene::class.java.name) { TankoubonDetailScene() }
        SceneFactory.register(TankDetailScene::class.java.name) { TankDetailScene() }
        SceneFactory.register(HistoryScene::class.java.name) { HistoryScene() }
        SceneFactory.register(ReadingStatsScene::class.java.name) { ReadingStatsScene() }
    }
}
