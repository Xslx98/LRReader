/*
 * Copyright 2016 Hippo Seven
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

import android.content.DialogInterface
import android.graphics.Paint
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputLayout
import com.hippo.easyrecyclerview.EasyRecyclerView
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.dao.BlackList
import com.hippo.util.DrawableManager
import com.hippo.util.IoThreadPoolExecutor
import com.hippo.util.TimeUtils
import com.hippo.view.ViewTransition
import com.hippo.lib.yorozuya.ViewUtils

class BlackListActivity : ToolbarActivity() {

    private var mRecyclerView: EasyRecyclerView? = null
    private var mViewTransition: ViewTransition? = null
    private var mAdapter: BlackListAdapter? = null
    private var mBlackListList: BlackListList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blacklist)
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24)

        mBlackListList = BlackListList()

        mRecyclerView = ViewUtils.`$$`(this, R.id.recycler_view1) as EasyRecyclerView
        val tip = ViewUtils.`$$`(this, R.id.tip) as TextView
        mViewTransition = ViewTransition(mRecyclerView, tip)

        val drawable = DrawableManager.getVectorDrawable(this, R.drawable.big_filter)
        drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        tip.setCompoundDrawables(null, drawable, null, null)

        mAdapter = BlackListAdapter()
        mRecyclerView?.apply {
            adapter = mAdapter
            clipToPadding = false
            clipChildren = false
            layoutManager = LinearLayoutManager(this@BlackListActivity)
            hasFixedSize()
            itemAnimator = null
        }

        updateView(false)
    }

    private fun updateView(animation: Boolean) {
        val viewTransition = mViewTransition ?: return

        if (mBlackListList == null || mBlackListList!!.size() == 0) {
            viewTransition.showView(1, animation)
        } else {
            viewTransition.showView(0, animation)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mRecyclerView = null
        mViewTransition = null
        mAdapter = null
        mBlackListList = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.activity_blick_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_add -> {
                showAddBlackListDialog()
                true
            }
            R.id.action_tip -> {
                showTipDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showTipDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.blacklist)
            .setMessage(R.string.blacklist_tip)
            .show()
    }

    private fun showAddBlackListDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_blacklist)
            .setView(R.layout.dialog_add_blacklist)
            .setPositiveButton(R.string.add, null)
            .show()
        val helper = AddBlackListDialogHelper()
        helper.setDialog(dialog)
    }

    private fun showDeleteBlackListDialog(blackList: BlackList) {
        val message = getString(R.string.delete_blacklist, blackList.badgayname)
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, which ->
                if (DialogInterface.BUTTON_POSITIVE != which || mBlackListList == null) {
                    return@setPositiveButton
                }
                mBlackListList!!.delete(blackList)
                mAdapter?.notifyDataSetChanged()
                updateView(true)
            }.show()
    }

    private inner class AddBlackListDialogHelper : View.OnClickListener {

        private var mDialog: AlertDialog? = null
        private var mSpinner: Spinner? = null
        private var mInputLayout: TextInputLayout? = null
        private var mEditText: EditText? = null

        fun setDialog(dialog: AlertDialog) {
            mDialog = dialog
            mSpinner = ViewUtils.`$$`(dialog, R.id.spinner) as? Spinner
            mInputLayout = ViewUtils.`$$`(dialog, R.id.text_inputreason_layout) as? TextInputLayout
            mEditText = mInputLayout?.editText
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            val blackListList = mBlackListList ?: return
            val dialog = mDialog ?: return
            val spinner = mSpinner ?: return
            val inputLayout = mInputLayout ?: return
            val editText = mEditText ?: return

            val text = editText.text.toString().trim()
            if (TextUtils.isEmpty(text)) {
                inputLayout.error = getString(R.string.text_is_empty)
                return
            } else {
                inputLayout.error = null
            }
            @Suppress("UNUSED_VARIABLE")
            val mode = spinner.selectedItemPosition

            val blackList = BlackList().apply {
                badgayname = text
                add_time = TimeUtils.timeNow
                angrywith = "/\u624B\u52A8\u6DFB\u52A0/"
                this.mode = 1
            }

            blackListList.add(blackList)

            mAdapter?.notifyDataSetChanged()
            updateView(true)

            dialog.dismiss()
            mDialog = null
            mSpinner = null
            mInputLayout = null
            mEditText = null
        }
    }

    private inner class BlackListHolder(itemView: View) :
        RecyclerView.ViewHolder(itemView), View.OnClickListener {

        val text: TextView = ViewUtils.`$$`(itemView, R.id.text) as TextView
        val icon: ImageView? = itemView.findViewById(R.id.icon)

        init {
            icon?.setOnClickListener(this)
            text.setOnClickListener(this)
        }

        override fun onClick(v: View) {
            val position = adapterPosition
            if (position < 0 || mBlackListList == null) {
                return
            }

            val blackList = mBlackListList!!.get(position)

            if (v is ImageView) {
                showDeleteBlackListDialog(blackList)
            } else if (v is TextView) {
                mAdapter?.notifyItemChanged(adapterPosition)
            }
        }
    }

    private inner class BlackListAdapter : RecyclerView.Adapter<BlackListHolder>() {

        override fun getItemViewType(position: Int): Int {
            if (mBlackListList == null) {
                return ADAPTER_TYPE_ITEM
            }
            return if (mBlackListList!!.get(position).mode == BLACKLIST_MODE_HEADER) {
                ADAPTER_TYPE_HEADER
            } else {
                ADAPTER_TYPE_ITEM
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlackListHolder {
            val layoutId = when (viewType) {
                ADAPTER_TYPE_HEADER -> R.layout.item_blacklist_header
                else -> R.layout.item_blacklist
            }

            val holder = BlackListHolder(layoutInflater.inflate(layoutId, parent, false))

            if (layoutId == R.layout.item_blacklist) {
                holder.icon?.setImageDrawable(
                    DrawableManager.getVectorDrawable(this@BlackListActivity, R.drawable.v_delete_x24)
                )
            }

            return holder
        }

        override fun onBindViewHolder(holder: BlackListHolder, position: Int) {
            val blackListList = mBlackListList ?: return
            val blackList = blackListList.get(position)

            if (BLACKLIST_MODE_HEADER == blackList.mode) {
                holder.text.text = blackList.badgayname
            } else {
                holder.text.text = blackList.badgayname
                holder.text.paintFlags = holder.text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
        }

        override fun getItemCount(): Int {
            return mBlackListList?.size() ?: 0
        }
    }

    private inner class BlackListList {

        private var mTitleBlackList: MutableList<BlackList> = mutableListOf()
        private var mTitleHeader: BlackList? = null

        init {
            IoThreadPoolExecutor.instance.execute {
                val result = EhDB.getAllBlackList()
                runOnUiThread {
                    mTitleBlackList = result.toMutableList()
                    mAdapter?.notifyDataSetChanged()
                }
            }
        }

        fun size(): Int {
            val size = mTitleBlackList.size
            return if (size == 0) 0 else size + 1
        }

        private fun getTitleHeader(): BlackList {
            if (mTitleHeader == null) {
                mTitleHeader = BlackList().apply {
                    mode = BLACKLIST_MODE_HEADER
                    badgayname = getString(R.string.blacklist_id)
                }
            }
            return mTitleHeader!!
        }

        fun get(index: Int): BlackList {
            val size = mTitleBlackList.size
            if (size != 0) {
                if (index == 0) {
                    return getTitleHeader()
                } else if (index <= size) {
                    return mTitleBlackList[index - 1]
                }
            }
            throw IndexOutOfBoundsException()
        }

        fun add(blackList: BlackList) {
            mTitleBlackList.add(blackList)
            IoThreadPoolExecutor.instance.execute {
                EhDB.insertBlackList(blackList)
            }
        }

        fun delete(blackList: BlackList) {
            mTitleBlackList.remove(blackList)
            IoThreadPoolExecutor.instance.execute {
                EhDB.deleteBlackList(blackList)
            }
        }
    }

    companion object {
        private const val ADAPTER_TYPE_ITEM = 0
        private const val ADAPTER_TYPE_HEADER = 1
        private const val BLACKLIST_MODE_HEADER = -1
    }
}
