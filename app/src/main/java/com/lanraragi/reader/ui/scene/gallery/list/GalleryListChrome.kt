package com.lanraragi.reader.ui.scene.gallery.list

import android.content.Context
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.lanraragi.reader.util.attrColor
import com.lanraragi.framework.drawable.AddDeleteDrawable
import com.lanraragi.framework.drawable.DrawerArrowDrawable
import com.lanraragi.framework.widget.SearchBarMover
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.client.api.LRRAuthManager

/**
 * The controls around the gallery list: the search bar (drawables, helpers,
 * suggestions, history), the search layout, the FAB menu and the search
 * bar's scroll-away mover.
 */
object GalleryListChrome {

    /** Wires the controls of the view just created in onCreateView2. */
    fun bind(
        scene: GalleryListScene,
        context: Context,
        mainLayout: View,
        paddingTopSB: Int,
        paddingBottomFab: Int,
    ) {
        val searchBar = scene.searchBar
        val searchLayout = scene.searchLayout
        val fabLayout = scene.fabLayout
        val searchBarHelper = scene.searchBarHelper

        scene.leftDrawable = DrawerArrowDrawable(context, context.attrColor(R.attr.drawableColorPrimary))
        scene.rightDrawable = AddDeleteDrawable(context, context.attrColor(R.attr.drawableColorPrimary))
        searchBar.setLeftDrawable(scene.leftDrawable)
        searchBar.setRightDrawable(scene.rightDrawable)
        searchBar.setHelper(searchBarHelper)
        searchBar.setOnStateChangeListener(searchBarHelper)
        GallerySearchHelper.setSearchBarHint(context, searchBar)
        scene.mSearchHelper?.let { searchBar.setSuggestionProvider(it.createSuggestionProvider()) }
        val historyStore = ProfileSearchHistoryStore(
            scene.viewLifecycleOwner.lifecycleScope,
            ServiceRegistry.dataModule.searchHistoryRepository,
        )
        scene.mHistoryStore = historyStore
        searchBar.setHistoryStore(historyStore)
        historyStore.refresh()

        searchLayout.setHelper(searchBarHelper)
        searchLayout.setPadding(
            searchLayout.paddingLeft, searchLayout.paddingTop + paddingTopSB,
            searchLayout.paddingRight, searchLayout.paddingBottom + paddingBottomFab
        )

        fabLayout.setAutoCancel(true)
        fabLayout.isExpanded = false
        fabLayout.setHidePrimaryFab(false)
        fabLayout.setOnClickFabListener(scene.mFabHelper)
        fabLayout.setOnExpandListener(scene.mFabHelper)
        scene.addAboveSnackView(fabLayout)

        scene.actionFabDrawable = AddDeleteDrawable(
            context, context.resources.getColor(R.color.primary_drawable_dark, null)
        )
        fabLayout.primaryFab.setImageDrawable(scene.actionFabDrawable)

        scene.searchFab.setOnClickListener(scene)

        if (LRRAuthManager.getServerUrl() == null) {
            val fabUpload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_upload)
            val fabUrlDownload: FloatingActionButton? = mainLayout.findViewById(R.id.fab_url_download)
            if (fabUpload != null) fabLayout.removeView(fabUpload)
            if (fabUrlDownload != null) fabLayout.removeView(fabUrlDownload)
        }

        scene.searchBarMover = SearchBarMover(
            checkNotNull(searchBarHelper), searchBar, scene.recyclerView, searchLayout
        )
    }
}
