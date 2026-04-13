package com.hippo.ehviewer.ui.scene.gallery.list

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hippo.drawable.AddDeleteDrawable
import com.hippo.drawable.DrawerArrowDrawable
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.GalleryInfoUi
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.widget.SearchBar
import com.hippo.ehviewer.widget.SearchLayout
import com.hippo.scene.Announcer
import com.hippo.view.ViewTransition
import com.hippo.widget.FabLayout
import com.hippo.widget.SearchBarMover
import androidx.recyclerview.widget.RecyclerView

/**
 * Factory that constructs all helpers for [GalleryListScene], wiring each
 * helper's Callback to the Scene's fields. Extracted to reduce GalleryListScene
 * line count by moving verbose anonymous-Callback construction out.
 *
 * All helpers are returned via [Result] so the Scene can assign them to its
 * fields in a single destructure.
 */
internal object GalleryListHelperFactory {

    data class Result(
        val filterHelper: GalleryFilterHelper,
        val goToHelper: GalleryGoToHelper,
        val stateHelper: GalleryStateHelper,
        val itemActionHelper: GalleryItemActionHelper,
        val tagChipHelper: GalleryTagChipHelper,
        val dataHelper: GalleryListDataHelper,
        val searchHelper: GallerySearchHelper,
        val listSearchHelper: GalleryListSearchHelper,
        val uploadHelper: GalleryUploadHelper,
        val searchBarHelper: GallerySearchBarHelper,
        val fabHelper: GalleryFabHelper,
        val drawerHelper: GalleryDrawerHelper
    )

    /**
     * Build all helpers for the given scene. Call this from
     * [GalleryListScene.onCreateView2] after view references are set.
     */
    @SuppressLint("RtlHardcoded")
    fun create(scene: GalleryListScene, context: Context): Result {
        val filterHelper = GalleryFilterHelper(object : GalleryFilterHelper.Callback {
            override fun getFilterFab(): FloatingActionButton? = scene.floatingActionButton
        })

        val goToHelper = GalleryGoToHelper(object : GalleryGoToHelper.Callback {
            override fun getHostContext(): Context? = scene.ehContext
            override fun getContentHelper() = scene.mHelper
            override fun getUrlBuilder(): ListUrlBuilder? = scene.mUrlBuilder
            override fun getLayoutInflater(): LayoutInflater = scene.layoutInflater
            override fun getString(resId: Int): String = scene.getString(resId)
            override fun getString(resId: Int, vararg formatArgs: Any): String =
                scene.getString(resId, *formatArgs)
        })

        val stateHelper = GalleryStateHelper(object : GalleryStateHelper.Callback {
            override fun getSearchBar(): SearchBar? = scene.searchBar
            override fun getSearchBarMover(): SearchBarMover? = scene.searchBarMover
            override fun getViewTransition(): ViewTransition? = scene.viewTransition
            override fun getSearchLayout(): SearchLayout? = scene.searchLayout
            override fun getFabLayout(): FabLayout? = scene.fabLayout
            override fun getSearchFab(): View? = scene.searchFab
            override fun getActionFabDrawable(): AddDeleteDrawable? = scene.actionFabDrawable
            override fun getLeftDrawable(): DrawerArrowDrawable? = scene.leftDrawable
            override fun getRightDrawable(): AddDeleteDrawable? = scene.rightDrawable
            override fun setDrawerLockMode(mode: Int, gravity: Int) =
                scene.setDrawerLockMode(mode, gravity)
        })

        val itemActionHelper = GalleryItemActionHelper(object : GalleryItemActionHelper.Callback {
            override fun getHostContext(): Context? = scene.ehContext
            override fun getHostActivity(): Activity? = scene.activity2
            override fun getLayoutInflater(): LayoutInflater = scene.layoutInflater
            override fun getDownloadManager(): DownloadManager = scene.downloadManager
            override fun getSceneFragment() = scene
            override fun startScene(announcer: Announcer) = scene.startScene(announcer)
            override fun getString(resId: Int): String = scene.getString(resId)
            override fun getString(resId: Int, vararg formatArgs: Any): String =
                scene.getString(resId, *formatArgs)
            override fun buildChipGroup(gi: GalleryInfoUi?, chipGroup: ChipGroup): ChipGroup =
                scene.tagChipHelper?.buildChipGroup(gi, chipGroup) ?: chipGroup
        })

        val tagChipHelper = GalleryTagChipHelper(object : GalleryTagChipHelper.Callback {
            override fun getHostContext(): Context? = scene.ehContext
            override fun requireContext(): Context = scene.requireContext()
            override fun getLayoutInflater(): LayoutInflater = scene.layoutInflater
            override fun isDrawersVisible(): Boolean = scene.isDrawersVisible()
            override fun closeDrawer(gravity: Int) = scene.closeDrawer(gravity)
            override fun getUrlBuilder(): ListUrlBuilder? = scene.mUrlBuilder
            override fun getContentHelper() = scene.mHelper
            override fun isFilterOpen(): Boolean = scene.filterHelper?.filterOpen ?: false
            override fun buildFilterSearch(tagName: String): String =
                scene.filterHelper?.searchTagBuild(tagName) ?: tagName
            override fun updateFilterDisplay() {
                scene.filterHelper?.updateFilterIcon(scene.filterHelper?.filterTagList?.size ?: 0)
            }
            override fun onUpdateUrlBuilder() = scene.onUpdateUrlBuilder()
            override fun setState(state: Int) { scene.stateHelper?.setState(state) }
            override fun onItemClick(view: View?, gi: GalleryInfoUi?): Boolean =
                scene.itemActionHelper?.onItemClick(view, gi) ?: false
            override fun onItemLongClick(gi: GalleryInfoUi?, view: View): Boolean =
                scene.itemActionHelper?.onItemLongClick(gi, view) ?: false
            override fun dismissItemDialog() { scene.itemActionHelper?.dismissDialog() }
            override fun getBaseScene(): BaseScene = scene
        })
        tagChipHelper.setEhTags(EhTagDatabase.getInstance(context))

        val dataHelper = GalleryListDataHelper(object : GalleryListDataHelper.Callback {
            override fun getHostContext(): Context? = scene.ehContext
            override fun getUrlBuilder(): ListUrlBuilder? = scene.mUrlBuilder
            override fun getSortBy(): String = scene.searchLayout?.sortBy ?: "date_added"
            override fun getSortOrder(): String = scene.searchLayout?.sortOrder ?: "desc"
            override fun notifyAdapterDataSetChanged() {
                scene.adapter?.notifyItemRangeChanged(0, scene.adapter?.itemCount ?: 0)
            }
            override fun notifyAdapterItemRangeRemoved(positionStart: Int, itemCount: Int) {
                scene.adapter?.notifyItemRangeRemoved(positionStart, itemCount)
            }
            override fun notifyAdapterItemRangeInserted(positionStart: Int, itemCount: Int) {
                scene.adapter?.notifyItemRangeInserted(positionStart, itemCount)
            }
            override fun showSearchBar() { scene.searchBarMover?.showSearchBar() }
            override fun showActionFab() { scene.stateHelper?.showActionFab() }
            override fun getString(resId: Int): String = scene.getString(resId)
        })

        val searchHelper = GallerySearchHelper(object : GallerySearchHelper.Callback {
            override fun getHostContext(): Context? = scene.ehContext
            override fun getHostResources(): Resources? = scene.resources2
            override fun navigateToScene(announcer: Announcer) = scene.startScene(announcer)
            override fun getSearchState(): Int = scene.stateHelper?.state ?: GalleryStateHelper.STATE_NORMAL
            override fun setSearchState(state: Int) { scene.stateHelper?.setState(state) }
        })

        val listSearchHelper = GalleryListSearchHelper(object : GalleryListSearchHelper.Callback {
            override fun getSearchBar(): SearchBar? = scene.searchBar
            override fun getSearchLayout(): SearchLayout? = scene.searchLayout
            override fun getUrlBuilder(): ListUrlBuilder? = scene.mUrlBuilder
            override fun getContentHelper(): GalleryListDataHelper? = scene.mHelper
            override fun getStateHelper(): GalleryStateHelper? = scene.stateHelper
            override fun getRecyclerView(): RecyclerView? = scene.recyclerView
            override fun onUpdateUrlBuilder() = scene.onUpdateUrlBuilder()
            override fun showTip(message: String, length: Int) = scene.showTip(message, length)
            override fun toggleDrawer(gravity: Int) = scene.toggleDrawer(gravity)
            override fun hideSoftInput() = scene.hideSoftInput()
        })

        val uploadHelper = GalleryUploadHelper(object : GalleryUploadHelper.Callback {
            override fun showTip(message: String, length: Int) = scene.showTip(message, length)
            override fun showTip(resId: Int, length: Int) = scene.showTip(resId, length)
            override fun refreshList() { scene.mHelper?.refresh() }
            override fun getHostActivity(): Activity? = scene.activity2
            override fun getHostContext(): Context? = scene.ehContext
            override fun getHostString(resId: Int): String = scene.getString(resId)
            override fun getHostString(resId: Int, vararg formatArgs: Any): String = scene.getString(resId, *formatArgs)
            override fun startActivityForResult(intent: Intent, requestCode: Int) =
                scene.startActivityForResult(intent, requestCode)
        })

        val searchBarHelper = GallerySearchBarHelper(
            listSearchHelper = { scene.listSearchHelper },
            stateHelper = { scene.stateHelper },
            searchBarMover = { scene.searchBarMover },
            contentHelper = { scene.mHelper },
            setDrawerLockMode = scene::setDrawerLockMode,
            doBackPress = scene::onBackPressed,
            doStartActivityForResult = scene::startActivityForResult,
            doGetString = scene::getString
        )

        val fabHelper = GalleryFabHelper(
            stateHelper = { scene.stateHelper },
            contentHelper = { scene.mHelper },
            filterHelper = { scene.filterHelper },
            goToHelper = { scene.goToHelper },
            itemActionHelper = { scene.itemActionHelper },
            uploadHelper = { scene.uploadHelper },
            urlBuilder = { scene.mUrlBuilder }
        )

        val drawerHelper = GalleryDrawerHelper(object : GalleryDrawerHelper.Callback {
            override fun getHostContext(): Context? = scene.ehContext
            override fun getHostActivity(): Activity? = scene.activity2
            override fun getScene(): GalleryListScene = scene
            override fun getSceneTag(): String? = scene.tag
            override fun getUrlBuilder(): ListUrlBuilder? = scene.mUrlBuilder
            override fun getEhTags(): EhTagDatabase? = scene.tagChipHelper?.getEhTags()
            override fun showTip(resId: Int, length: Int) = scene.showTip(resId, length)
            override fun showTip(message: String, length: Int) = scene.showTip(message, length)
            override fun getString(resId: Int): String = scene.getString(resId)
            override fun getString(resId: Int, vararg formatArgs: Any): String =
                scene.getString(resId, *formatArgs)
            override fun onTagClick(tagName: String) { scene.tagChipHelper?.onTagClick(tagName) }
        })

        return Result(
            filterHelper = filterHelper,
            goToHelper = goToHelper,
            stateHelper = stateHelper,
            itemActionHelper = itemActionHelper,
            tagChipHelper = tagChipHelper,
            dataHelper = dataHelper,
            searchHelper = searchHelper,
            listSearchHelper = listSearchHelper,
            uploadHelper = uploadHelper,
            searchBarHelper = searchBarHelper,
            fabHelper = fabHelper,
            drawerHelper = drawerHelper
        )
    }
}
