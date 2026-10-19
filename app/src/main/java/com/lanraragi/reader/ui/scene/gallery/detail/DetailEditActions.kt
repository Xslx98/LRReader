package com.lanraragi.reader.ui.scene.gallery.detail

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import com.lanraragi.framework.scene.Announcer
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.ui.scene.TankoubonDetailScene

/**
 * The detail page's edit entry points: the tag editor and the tankoubon
 * section (open a tank, edit the archive's tank membership).
 */
class DetailEditActions(
    private val scene: GalleryDetailScene,
    private val viewModel: GalleryDetailViewModel,
) {

    /** Shows the tag-edit button on LANraragi servers; a save refreshes the page. */
    fun bindEditTagsButton(button: ImageButton?) {
        button?.let { btn ->
            val isLrr = LRRAuthManager.getServerUrl() != null
            btn.visibility = if (isLrr) View.VISIBLE else View.GONE
            btn.setOnClickListener {
                val ad = viewModel.archiveDetail.value
                if (ad != null) {
                    TagEditDialog.show(
                        scene.activity2, ad.archive.arcid,
                        ad.tagGroups, viewModel.getSourceProfileId()
                    ) {
                        scene.requestRefresh()
                    }
                }
            }
        }
    }

    fun openTankoubonDetail(tank: LRRTankoubonApi.Tankoubon) {
        val args = Bundle().apply {
            putString(TankoubonDetailScene.KEY_TANK_ID, tank.id)
            putString(TankoubonDetailScene.KEY_TANK_NAME, tank.name)
            putLong(TankoubonDetailScene.KEY_PROFILE_ID, viewModel.getSourceProfileId())
        }
        scene.startScene(Announcer(TankoubonDetailScene::class.java).setArgs(args))
    }

    fun showTankMembershipDialog() {
        val arcid = viewModel.getEffectiveArcid() ?: return
        TankoubonDialogHelper.showMembershipDialog(
            scene.activity2, arcid, viewModel.getSourceProfileId(),
            archiveTitle = viewModel.getEffectiveArchive()?.title,
        ) { viewModel.loadArchiveTankoubons() }
    }
}
