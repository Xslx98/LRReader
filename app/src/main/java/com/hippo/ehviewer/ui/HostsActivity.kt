/*
 * Copyright 2018 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Pair
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import com.hippo.android.resource.AttrResources
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.easyrecyclerview.LinearDividerItemDecoration
import com.hippo.ehviewer.Hosts
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ripple.Ripple
import com.hippo.lib.yorozuya.LayoutUtils
import java.util.Locale

class HostsActivity : ToolbarActivity(),
    EasyRecyclerView.OnItemClickListener, View.OnClickListener {

    private lateinit var hosts: Hosts
    private lateinit var data: List<Pair<String, String>>

    // Snapshot of the list last dispatched to the adapter. Read/written ONLY by
    // the single dispatch path (notifyHostsChanges + onCreate initial load).
    // See docs/diffutil-root-cause-analysis.md for the snapshot ownership rule.
    private var lastSnapshot: List<Pair<String, String>> = emptyList()

    private lateinit var recyclerView: EasyRecyclerView
    private lateinit var tip: View
    private lateinit var adapter: HostsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hosts = ServiceRegistry.networkModule.hosts
        data = hosts.getAll()
        // Initial baseline so the first notifyHostsChanges() diff matches the
        // adapter's first onBindViewHolder pass.
        lastSnapshot = data.toList()

        setContentView(R.layout.activity_hosts)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)
        recyclerView = findViewById(R.id.recycler_view)
        tip = findViewById(R.id.tip)
        val fab: FloatingActionButton = findViewById(R.id.fab)

        adapter = HostsAdapter()
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        val decoration = LinearDividerItemDecoration(
            LinearDividerItemDecoration.VERTICAL,
            AttrResources.getAttrColor(this, R.attr.dividerColor),
            LayoutUtils.dp2pix(this, 1f)
        )
        decoration.setShowLastDivider(true)
        recyclerView.addItemDecoration(decoration)
        recyclerView.setSelector(
            Ripple.generateRippleDrawable(
                this,
                !AttrResources.getAttrBoolean(this, androidx.appcompat.R.attr.isLightTheme),
                ColorDrawable(Color.TRANSPARENT)
            )
        )
        recyclerView.setHasFixedSize(true)
        recyclerView.setOnItemClickListener(this)
        recyclerView.setPadding(
            recyclerView.paddingLeft,
            recyclerView.paddingTop,
            recyclerView.paddingRight,
            recyclerView.paddingBottom + resources.getDimensionPixelOffset(R.dimen.gallery_padding_bottom_fab)
        )

        fab.setOnClickListener(this)

        recyclerView.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
        tip.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onItemClick(easyRecyclerView: EasyRecyclerView, view: View, position: Int, id: Long): Boolean {
        val pair = data[position]
        val args = Bundle().apply {
            putString(KEY_HOST, pair.first)
            putString(KEY_IP, pair.second)
        }

        val fragment = EditHostDialogFragment()
        fragment.arguments = args
        fragment.show(supportFragmentManager, DIALOG_TAG_EDIT_HOST)

        return true
    }

    override fun onClick(v: View) {
        AddHostDialogFragment().show(supportFragmentManager, DIALOG_TAG_ADD_HOST)
    }

    private fun notifyHostsChanges() {
        val newData = hosts.getAll()
        val diff = DiffUtil.calculateDiff(HostPairDiffCallback(lastSnapshot, newData))
        data = newData
        lastSnapshot = newData.toList()
        recyclerView.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
        tip.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
        diff.dispatchUpdatesTo(adapter)
    }

    /**
     * DiffUtil callback for host/IP pair lists. Identity is the host name
     * (unique key in Hosts). Content is the IP value.
     */
    private class HostPairDiffCallback(
        private val oldList: List<Pair<String, String>>,
        private val newList: List<Pair<String, String>>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].first == newList[newItemPosition].first
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].second == newList[newItemPosition].second
        }
    }

    private class HostsHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val host: TextView = itemView.findViewById(R.id.host)
        val ip: TextView = itemView.findViewById(R.id.ip)
    }

    private inner class HostsAdapter : RecyclerView.Adapter<HostsHolder>() {

        private val inflater: LayoutInflater = layoutInflater

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HostsHolder {
            return HostsHolder(inflater.inflate(R.layout.item_hosts, parent, false))
        }

        override fun onBindViewHolder(holder: HostsHolder, position: Int) {
            val pair = data[position]
            holder.host.text = pair.first
            holder.ip.text = pair.second
        }

        override fun getItemCount(): Int = data.size
    }

    abstract class HostDialogFragment : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val view = requireActivity().layoutInflater.inflate(R.layout.dialog_hosts, null, false)
            val host = view.findViewById<TextView>(R.id.host)
            val ip = view.findViewById<TextView>(R.id.ip)

            val arguments = arguments
            if (savedInstanceState == null && arguments != null) {
                host.text = arguments.getString(KEY_HOST)
                ip.text = arguments.getString(KEY_IP)
            }

            val builder = AlertDialog.Builder(requireContext()).setView(view)
            onCreateDialogBuilder(builder)
            val dialog = builder.create()
            dialog.setOnShowListener { d -> onCreateDialog(d as AlertDialog) }

            return dialog
        }

        protected abstract fun onCreateDialogBuilder(builder: AlertDialog.Builder)

        protected abstract fun onCreateDialog(dialog: AlertDialog)

        protected fun put(dialog: AlertDialog) {
            val host = dialog.findViewById<TextView>(R.id.host)!!
            val ip = dialog.findViewById<TextView>(R.id.ip)!!
            val hostString = host.text.toString().trim().lowercase(Locale.US)
            val ipString = ip.text.toString().trim()

            if (!Hosts.isValidHost(hostString)) {
                val hostInputLayout = dialog.findViewById<TextInputLayout>(R.id.host_input_layout)!!
                hostInputLayout.error = requireContext().getString(R.string.invalid_host)
                return
            }

            if (!Hosts.isValidIp(ipString)) {
                val ipInputLayout = dialog.findViewById<TextInputLayout>(R.id.ip_input_layout)!!
                ipInputLayout.error = requireContext().getString(R.string.invalid_ip)
                return
            }

            val activity = dialog.ownerActivity as HostsActivity
            activity.hosts.put(hostString, ipString)
            activity.notifyHostsChanges()

            dialog.dismiss()
        }

        protected fun delete(dialog: AlertDialog) {
            val host = dialog.findViewById<TextView>(R.id.host)!!
            val hostString = host.text.toString().trim().lowercase(Locale.US)

            val activity = dialog.ownerActivity as HostsActivity
            activity.hosts.delete(hostString)
            activity.notifyHostsChanges()

            dialog.dismiss()
        }
    }

    class AddHostDialogFragment : HostDialogFragment() {

        override fun onCreateDialogBuilder(builder: AlertDialog.Builder) {
            builder.setTitle(R.string.add_host)
            builder.setPositiveButton(R.string.add_host_add, null)
        }

        override fun onCreateDialog(dialog: AlertDialog) {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { put(dialog) }
        }
    }

    class EditHostDialogFragment : HostDialogFragment() {

        override fun onCreateDialogBuilder(builder: AlertDialog.Builder) {
            builder.setTitle(R.string.edit_host)
            builder.setPositiveButton(R.string.edit_host_confirm, null)
            builder.setNegativeButton(R.string.edit_host_delete, null)
        }

        override fun onCreateDialog(dialog: AlertDialog) {
            dialog.findViewById<View>(R.id.host_input_layout)!!.isEnabled = false
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { put(dialog) }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { delete(dialog) }
        }
    }

    companion object {
        private val DIALOG_TAG_ADD_HOST = AddHostDialogFragment::class.java.name
        private val DIALOG_TAG_EDIT_HOST = EditHostDialogFragment::class.java.name

        private const val KEY_HOST = "com.hippo.ehviewer.ui.HostsActivity.HOST"
        private const val KEY_IP = "com.hippo.ehviewer.ui.HostsActivity.IP"
    }
}
