package com.lanraragi.reader.ui.scene.download

import android.content.Context
import android.graphics.Color
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.hippo.android.resource.AttrResources
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.FastScroller
import com.hippo.easyrecyclerview.HandlerDrawable
import com.hippo.easyrecyclerview.MarginItemDecoration
import com.lanraragi.reader.ui.widget.Ripples
import com.lanraragi.framework.drawable.AddDeleteDrawable
import com.lanraragi.framework.widget.FabLayout
import com.lanraragi.framework.widget.recyclerview.AutoStaggeredGridLayoutManager
import com.lanraragi.reader.R
import com.lanraragi.reader.settings.AppearanceSettings
import com.lanraragi.reader.ui.scene.download.part.DownloadAdapter.Companion.DRAG_ENABLE
import com.lanraragi.reader.widget.MyEasyRecyclerView

/** How the Downloads page's list, fast scroller and FAB menu are set up. */
internal object DownloadListViews {

    /** Lays out and wires the download list; returns its layout manager. */
    fun configureList(
        scene: DownloadsScene,
        context: Context,
        recyclerView: MyEasyRecyclerView,
        selectionHelper: DownloadSelectionHelper?,
        dragDropHelper: DownloadDragDropHelper?,
    ): AutoStaggeredGridLayoutManager {
        val resources = context.resources
        val layoutManager = AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL)
        layoutManager.setColumnSize(resources.getDimensionPixelOffset(AppearanceSettings.getDetailSizeResId()))
        layoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE)

        // Configure drag-related RecyclerView properties
        dragDropHelper?.configureRecyclerView(recyclerView)

        recyclerView.setItemViewCacheSize(100)
        recyclerView.layoutManager = layoutManager
        recyclerView.selector = Ripples.rippleDrawable(
            !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme),
            Color.TRANSPARENT.toDrawable()
        )
        recyclerView.setDrawSelectorOnTop(true)
        recyclerView.clipToPadding = false
        recyclerView.setOnItemClickListener(scene)
        recyclerView.setOnItemLongClickListener(scene)
        recyclerView.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM)
        recyclerView.setCustomCheckedListener(selectionHelper?.choiceListener)
        selectionHelper?.attachDragSelect(recyclerView)
        val interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval)
        val paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h)
        val paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v)
        val decoration = MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV)
        recyclerView.addItemDecoration(decoration)
        decoration.applyPaddings(recyclerView)

        // Attach drag-drop manager to RecyclerView
        dragDropHelper?.attachToRecyclerView(recyclerView)
        return layoutManager
    }

    fun configureFastScroller(
        context: Context,
        fastScroller: FastScroller,
        recyclerView: MyEasyRecyclerView,
        listener: FastScroller.OnDragHandlerListener,
    ) {
        fastScroller.attachToRecyclerView(recyclerView)
        val handlerDrawable = HandlerDrawable()
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent))
        fastScroller.setHandlerDrawable(handlerDrawable)
        fastScroller.setOnDragHandlerListener(listener)
    }

    /** Sets up the FAB menu; returns the primary FAB's drawable. */
    fun configureFab(scene: DownloadsScene, context: Context, fabLayout: FabLayout): AddDeleteDrawable {
        val resources = context.resources
        fabLayout.setExpanded(false, true)
        fabLayout.setHidePrimaryFab(false)
        fabLayout.setAutoCancel(false)
        fabLayout.setOnClickFabListener(scene)
        fabLayout.setOnExpandListener(scene)
        val actionFabDrawable = AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null))
        fabLayout.primaryFab.setImageDrawable(actionFabDrawable)
        val fab = fabLayout.getSecondaryFabAt(6)
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(scene.resources, R.drawable.v_mobile_hand_left_x24, context.theme))
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(scene.resources, R.drawable.v_mobile_hand_left_off_x24, context.theme))
        }
        return actionFabDrawable
    }
}
