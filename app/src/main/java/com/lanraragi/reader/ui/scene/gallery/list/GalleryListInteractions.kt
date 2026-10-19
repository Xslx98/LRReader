package com.lanraragi.reader.ui.scene.gallery.list

import android.view.View
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.LRRClientProvider
import com.lanraragi.reader.client.api.TankoubonSupportGate
import com.lanraragi.reader.client.api.isTankoubonId
import com.lanraragi.reader.client.data.ListUrlBuilder
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.ReadingContext
import com.lanraragi.reader.gallery.ReadingContextStore
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.ui.scene.BaseScene

/**
 * What a tap, long press or back press does on the gallery list: open or
 * toggle a row, publish the reading context for "continue to the next
 * archive", and unwind multi-select, the FAB menu, the tag filter and the
 * search before leaving.
 */
class GalleryListInteractions(private val scene: GalleryListScene) {

    private var pressBackTime: Long = 0

    fun onItemClick(view: View, position: Int): Boolean {
        val archive = scene.mHelper?.getDataAtEx(position)
        // In multi-select mode a click toggles the row instead of navigating —
        // the library does not toggle on click by itself (mirrors downloads).
        // Tank pseudo-entries are NOT selectable (batch ops don't apply).
        scene.multiSelectHelper?.let {
            if (it.isActive) {
                if (archive != null && isTankoubonId(archive.arcid)) return true
                return it.toggleChecked(position)
            }
        }
        // No reading context for tank rows: they never anchor an OnlineSearch
        // continuation (reading a member publishes ReadingContext.Tankoubon).
        if (archive != null && !isTankoubonId(archive.arcid)) {
            publishReadingContext(archive, position)
        }
        // Tank rows route to TankDetailScene inside the helper funnel.
        return scene.itemActionHelper?.onItemClick(view, archive) ?: false
    }

    /**
     * Capture the browse/search context so the reader can offer "continue to the
     * next archive in this list". Best-effort: must never break navigation.
     */
    private fun publishReadingContext(archive: Archive, position: Int) {
        val baseUrl = LRRClientProvider.getBaseUrl()
        val params = scene.shownSearch.forReadingContext()
        ReadingContextStore.publish(
            ReadingContext.OnlineSearch(
                sourceProfileId = LRRAuthManager.getActiveProfileId(),
                sourceBaseUrl = baseUrl,
                filter = params.filter,
                category = params.category,
                sortby = params.sortby,
                order = params.order,
                newonly = params.newonly,
                untaggedonly = params.untaggedonly,
                anchorArcid = archive.arcid,
                anchorIndex = position,
                // Same fold decision the paging source made for this list, so
                // resolver windows reproduce the raw indexing the user saw.
                groupbyTanks = AppearanceSettings.getGroupTanks() &&
                    !TankoubonSupportGate.isUnsupported(baseUrl),
            )
        )
    }

    /** Long-press enters multi-select — except on tank pseudo-entries. */
    fun onItemLongClick(position: Int): Boolean {
        val archive = scene.mHelper?.getDataAtEx(position)
        if (archive != null && isTankoubonId(archive.arcid)) return true // swallow: no menu, no selection
        return scene.multiSelectHelper?.enterAndCheck(position) ?: false
    }

    fun onBackPressed() {
        scene.tagChipHelper?.dismissPopup()
        val multiSelect = scene.multiSelectHelper
        if (multiSelect != null && multiSelect.isActive) {
            multiSelect.exit()
            return
        }

        if (scene.isFabLayoutCreated && scene.fabLayout.isExpanded) {
            scene.fabLayout.isExpanded = false
            return
        }

        val filterHelper = scene.filterHelper
        val urlBuilder = scene.mUrlBuilder
        if (filterHelper != null && urlBuilder != null && removeLastOpenFilterTag(filterHelper)) {
            urlBuilder.set(
                filterHelper.listToString(filterHelper.filterTagList),
                ListUrlBuilder.MODE_FILTER
            )
            filterHelper.updateFilterIcon(filterHelper.filterTagList.size)

            urlBuilder.pageIndex = 0
            scene.onUpdateUrlBuilder()
            scene.mHelper?.refresh()
            scene.stateHelper?.setState(GalleryStateHelper.STATE_NORMAL)
            return
        }

        val handle = scene.listSearchHelper?.handleSearchBackPress() ?: false
        if (!handle && !checkDoubleClickExit()) {
            scene.finish()
        }
    }

    /** Drops the last tag of an open tag filter; false when the filter is closed or empty. */
    private fun removeLastOpenFilterTag(filterHelper: GalleryFilterHelper): Boolean =
        filterHelper.filterOpen && filterHelper.removeLastFilterTag()

    private fun checkDoubleClickExit(): Boolean {
        if (scene.stackIndex != 0) {
            return false
        }
        val time = System.currentTimeMillis()
        return if (time - pressBackTime > BACK_PRESSED_INTERVAL) {
            pressBackTime = time
            scene.showTip(R.string.press_twice_exit, BaseScene.LENGTH_SHORT)
            true
        } else {
            false
        }
    }

    private companion object {
        const val BACK_PRESSED_INTERVAL = 2000
    }
}
