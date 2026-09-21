package com.lanraragi.reader.ui.scene.gallery.detail

import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.LRRTankoubonApi

/**
 * Binds the detail page's "belongs to Tankoubons" section. Passive like the
 * other Detail*Binder classes: the Scene collects
 * [GalleryDetailViewModel.archiveTankoubons] and calls [bind]; `null` keeps
 * the whole section GONE (pre-0.9.8 servers, lookup failure), an empty list
 * shows a "not in any tankoubon" placeholder so the Edit entry is still
 * reachable for adding the archive to one.
 */
internal class DetailTankoubonBinder(
    private val section: View,
    private val container: LinearLayout,
    edit: TextView,
    private val onTankClick: (LRRTankoubonApi.Tankoubon) -> Unit,
    onEditClick: () -> Unit,
) {

    init {
        edit.setOnClickListener { onEditClick() }
    }

    fun bind(tanks: List<LRRTankoubonApi.Tankoubon>?) {
        container.removeAllViews()
        if (tanks == null) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE
        val ctx = container.context
        val pad = (ROW_PADDING_DP * ctx.resources.displayMetrics.density).toInt()
        if (tanks.isEmpty()) {
            val placeholder = TextView(ctx)
            placeholder.setText(R.string.detail_tankoubons_none)
            placeholder.setPadding(0, pad, 0, pad)
            container.addView(placeholder)
            return
        }
        val selectable = TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        for (tank in tanks) {
            val row = TextView(ctx)
            val count = ctx.resources.getQuantityString(
                R.plurals.lrr_category_archives, tank.archives.size, tank.archives.size
            )
            row.text = "${tank.name} ($count)"
            row.setPadding(0, pad, 0, pad)
            row.setBackgroundResource(selectable.resourceId)
            row.setOnClickListener { onTankClick(tank) }
            container.addView(row)
        }
    }

    private companion object {
        const val ROW_PADDING_DP = 8
    }
}
