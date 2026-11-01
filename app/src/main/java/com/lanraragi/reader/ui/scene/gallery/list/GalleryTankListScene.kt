package com.lanraragi.reader.ui.scene.gallery.list

/**
 * The drawer's Tankoubons entry: the gallery list in tank mode
 * ([GalleryListScene.isTankMode]). Its own scene class so it gets its own
 * launch mode and stacks above the gallery instead of re-arguing the
 * single-top gallery instance beneath it.
 */
class GalleryTankListScene : GalleryListScene() {
    override val isTankMode: Boolean get() = true
}
