package com.hippo.ehviewer.ui.scene

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.appbar.MaterialToolbar
import com.hippo.ehviewer.R

/**
 * Detail scene for a single tankoubon: its ordered member archives.
 * Skeleton — fleshed out in Task 7. For now it only shows the tank name
 * in the toolbar with a working back arrow.
 */
class TankoubonDetailScene : BaseScene() {

    override fun getNavCheckedItem(): Int = R.id.nav_tankoubons

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.scene_tankoubons, container, false)

        view.findViewById<MaterialToolbar>(R.id.toolbar)?.apply {
            title = arguments?.getString(KEY_TANK_NAME).orEmpty()
            setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
            setNavigationOnClickListener { onBackPressed() }
        }

        return view
    }

    companion object {
        const val KEY_TANK_ID = "tank_id"
        const val KEY_TANK_NAME = "tank_name"
        const val KEY_PROFILE_ID = "tank_profile_id"
    }
}
