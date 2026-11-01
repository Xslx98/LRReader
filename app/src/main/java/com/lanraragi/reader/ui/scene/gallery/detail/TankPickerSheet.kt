package com.lanraragi.reader.ui.scene.gallery.detail

import android.annotation.SuppressLint
import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lanraragi.framework.widget.LoadImageViewNew
import com.lanraragi.reader.R
import com.lanraragi.reader.client.LRRCacheKeyFactory
import com.lanraragi.reader.client.TankCoverCacheStamp
import com.lanraragi.reader.client.api.LRRTankoubonApi
import com.lanraragi.reader.client.api.LRRTankoubonApi.Tankoubon
import com.lanraragi.reader.tankoubon.TankPickerRows
import com.lanraragi.reader.tankoubon.TankPickerRows.Section
import com.lanraragi.reader.ui.scene.TankDialogs

/**
 * Searchable bottom sheet over a server's full tank list
 * (docs/plan-2.4.0-tank-ux.md, PR-A). Opens at half height and drags to
 * near full screen; focusing the search field expands it. Rows come from
 * [TankPickerRows]: recent tanks first, then all in natural order, or one
 * ranked list while a query is typed.
 *
 * The header's create row turns into "New “query”" while searching and then
 * creates without asking again; with no query it opens the name dialog
 * prefilled with [suggestedName]. Either way [onCreate] receives the final
 * name after the sheet is dismissed — the caller owns the network write.
 */
internal class TankPickerSheet(
    private val activity: Activity,
    private val serverUrl: String,
    private val tanks: List<Tankoubon>,
    private val recentIds: List<String>,
    private val suggestedName: String?,
    private val mode: Mode,
    private val onCreate: (name: String) -> Unit,
) {

    sealed interface Mode {
        val selection: List<String>

        /** Batch add: one tap picks; tanks already holding the whole selection are greyed. */
        class Pick(
            override val selection: List<String>,
            val onPick: (Tankoubon) -> Unit,
        ) : Mode

        /**
         * One archive's memberships: rows toggle [checked] (live — the caller
         * reads it back to reopen after a create), Apply hands it to [onApply].
         */
        class Membership(
            arcid: String,
            val checked: MutableSet<String>,
            val onApply: (Set<String>) -> Unit,
        ) : Mode {
            override val selection: List<String> = listOf(arcid)
        }
    }

    private sealed interface Item {
        data class Header(val section: Section) : Item
        data class Tank(val row: TankPickerRows.Row, val checked: Boolean) : Item
    }

    private lateinit var sheet: BottomSheetDialog
    private val adapter = Adapter()
    private var query = ""

    @SuppressLint("InflateParams") // dialog content: no parent to resolve layout params against
    fun show() {
        sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_tank_picker, null, false)
        val search = view.findViewById<EditText>(R.id.tank_picker_search)
        val createText = view.findViewById<TextView>(R.id.tank_picker_create_text)
        val createHint = view.findViewById<View>(R.id.tank_picker_create_hint)
        val empty = view.findViewById<View>(R.id.tank_picker_empty)
        val list = view.findViewById<RecyclerView>(R.id.tank_picker_list)

        view.findViewById<TextView>(R.id.tank_picker_title).setText(R.string.tank_add_to)
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter

        fun render() {
            val items = buildItems()
            adapter.submitList(items)
            empty.visibility = if (items.isEmpty() && query.isNotEmpty()) View.VISIBLE else View.GONE
            createText.text = if (query.isEmpty()) {
                activity.getString(R.string.tank_create)
            } else {
                activity.getString(R.string.tank_picker_new_named, query)
            }
            createHint.visibility = if (TankPickerRows.hasExactName(tanks, query)) View.VISIBLE else View.GONE
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                render()
            }
        })
        search.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        view.findViewById<View>(R.id.tank_picker_create).setOnClickListener { onCreateClick() }

        if (mode is Mode.Membership) {
            view.findViewById<View>(R.id.tank_picker_buttons).visibility = View.VISIBLE
            view.findViewById<View>(R.id.tank_picker_cancel).setOnClickListener { sheet.dismiss() }
            view.findViewById<View>(R.id.tank_picker_apply).setOnClickListener {
                sheet.dismiss()
                mode.onApply(mode.checked.toSet())
            }
        }
        render()

        sheet.setContentView(view)
        val screenH = activity.resources.displayMetrics.heightPixels
        view.layoutParams = view.layoutParams.apply { height = (screenH * FULL_FRACTION).toInt() }
        sheet.behavior.peekHeight = (screenH * PEEK_FRACTION).toInt()
        if (mode is Mode.Membership) {
            // Apply sits at the sheet's bottom edge: a half-height sheet would hide it.
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.show()
    }

    private fun onCreateClick() {
        sheet.dismiss()
        if (query.isNotEmpty()) {
            onCreate(query)
        } else {
            TankDialogs.showNameInputDialog(
                activity, R.string.tank_create, suggestedName.orEmpty(), selectInitial = true, onOk = onCreate,
            )
        }
    }

    private fun buildItems(): List<Item> {
        val rows = TankPickerRows.build(
            tanks, mode.selection, recentIds, query, pinMembers = mode is Mode.Membership,
        )
        val checked = (mode as? Mode.Membership)?.checked
        val items = ArrayList<Item>(rows.size + Section.entries.size)
        // A lone "All" section needs no header; MATCH never gets one.
        val showHeaders = rows.any { it.section != Section.ALL && it.section != Section.MATCH }
        var last: Section? = null
        for (row in rows) {
            if (showHeaders && row.section != last) items.add(Item.Header(row.section))
            last = row.section
            items.add(Item.Tank(row, checked?.contains(row.tank.id) == true))
        }
        return items
    }

    private fun onRowClick(item: Item.Tank) {
        val tank = item.row.tank
        when (mode) {
            is Mode.Pick -> {
                sheet.dismiss()
                mode.onPick(tank)
            }
            is Mode.Membership -> {
                if (!mode.checked.remove(tank.id)) mode.checked.add(tank.id)
                adapter.submitList(buildItems())
            }
        }
    }

    private fun sectionTitle(section: Section): Int = when (section) {
        Section.MEMBER -> R.string.tank_picker_section_member
        Section.RECENT -> R.string.tank_picker_section_recent
        Section.ALL, Section.MATCH -> R.string.tank_picker_section_all
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: LoadImageViewNew = view.findViewById(R.id.tank_picker_thumb)
        val name: TextView = view.findViewById(R.id.tank_picker_name)
        val meta: TextView = view.findViewById(R.id.tank_picker_meta)
        val check: CheckBox = view.findViewById(R.id.tank_picker_check)
    }

    private inner class Adapter : ListAdapter<Item, RecyclerView.ViewHolder>(DIFF) {

        override fun getItemViewType(position: Int): Int =
            if (getItem(position) is Item.Header) TYPE_HEADER else TYPE_TANK

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_tank_picker_header, parent, false)) {}
            } else {
                RowHolder(inflater.inflate(R.layout.item_tank_picker, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is Item.Header -> (holder.itemView as TextView).setText(sectionTitle(item.section))
                is Item.Tank -> bindTank(holder as RowHolder, item)
            }
        }

        private fun bindTank(holder: RowHolder, item: Item.Tank) {
            val row = item.row
            val tank = row.tank
            val stamp = TankCoverCacheStamp.get(tank.id)
            holder.thumb.load(
                LRRCacheKeyFactory.getThumbKey("${tank.id}#$stamp"),
                LRRTankoubonApi.getTankoubonThumbnailUrl(serverUrl, tank.id, cacheBust = stamp),
            )
            holder.name.text = tank.name
            val res = holder.itemView.resources
            val count = res.getQuantityString(R.plurals.lrr_category_archives, tank.archives.size, tank.archives.size)
            val picking = mode is Mode.Pick
            holder.meta.text = when {
                !picking || row.contained == 0 -> count
                row.containsAll -> res.getString(R.string.tank_picker_meta, count, res.getString(R.string.tank_picker_contains_all))
                else -> res.getString(
                    R.string.tank_picker_meta, count,
                    res.getString(R.string.tank_picker_contains, row.contained, mode.selection.size),
                )
            }
            val enabled = !(picking && row.containsAll)
            holder.itemView.isEnabled = enabled
            holder.itemView.alpha = if (enabled) 1f else DISABLED_ALPHA
            holder.check.visibility = if (picking) View.GONE else View.VISIBLE
            holder.check.isChecked = item.checked
            holder.itemView.setOnClickListener { if (enabled) onRowClick(item) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TANK = 1
        const val FULL_FRACTION = 0.9
        const val PEEK_FRACTION = 0.5
        const val DISABLED_ALPHA = 0.38f

        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = when {
                oldItem is Item.Header && newItem is Item.Header -> oldItem.section == newItem.section
                oldItem is Item.Tank && newItem is Item.Tank -> oldItem.row.tank.id == newItem.row.tank.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem == newItem
        }
    }
}
