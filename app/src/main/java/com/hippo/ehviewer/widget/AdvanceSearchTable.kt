/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.widget

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import com.hippo.ehviewer.R
import com.hippo.lib.yorozuya.NumberUtils

class AdvanceSearchTable @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private lateinit var mSname: CheckBox
    private lateinit var mStags: CheckBox
    private lateinit var mSdesc: CheckBox
    private lateinit var mStorr: CheckBox
    private lateinit var mSto: CheckBox
    private lateinit var mSdt1: CheckBox
    private lateinit var mSdt2: CheckBox
    private lateinit var mSh: CheckBox
    private lateinit var mSr: CheckBox
    private lateinit var mMinRating: Spinner
    private lateinit var mSp: CheckBox
    private lateinit var mSpf: EditText
    private lateinit var mSpt: EditText
    private lateinit var mSfl: CheckBox
    private lateinit var mSfu: CheckBox
    private lateinit var mSft: CheckBox

    init {
        init()
    }

    fun init() {
        orientation = VERTICAL

        LayoutInflater.from(context).inflate(R.layout.widget_advance_search_table, this)

        mSname = findViewById(R.id.search_gallery)
        mStags = findViewById(R.id.search_gallery_tags)
        mSdesc = findViewById(R.id.search_gallery_description)
        mStorr = findViewById(R.id.search_torrent_filenames)
        mSto = findViewById(R.id.only_show_galleries_with_torrents)
        mSdt1 = findViewById(R.id.search_low_power_tags)
        mSdt2 = findViewById(R.id.search_downvoted_tags)
        mSh = findViewById(R.id.search_expunged_galleries)
        mSr = findViewById(R.id.minimum_rating)
        mMinRating = findViewById(R.id.search_min_rating)
        mSp = findViewById(R.id.pages_setting)
        mSpf = findViewById(R.id.spf)
        mSpt = findViewById(R.id.spt)
        mSfl = findViewById(R.id.disable_default_filter_language)
        mSfu = findViewById(R.id.disable_default_filter_uploader)
        mSft = findViewById(R.id.disable_default_filter_tags)

        // Avoid java.lang.IllegalStateException: focus search returned a view
        // that wasn't able to take focus!
        mSpt.setOnEditorActionListener { v, _, _ ->
            val nextView = v.focusSearch(View.FOCUS_DOWN)
            nextView?.requestFocus(View.FOCUS_DOWN)
            true
        }
    }

    fun getAdvanceSearch(): Int {
        var advanceSearch = 0
        if (mSname.isChecked) advanceSearch = advanceSearch or SNAME
        if (mStags.isChecked) advanceSearch = advanceSearch or STAGS
        if (mSdesc.isChecked) advanceSearch = advanceSearch or SDESC
        if (mStorr.isChecked) advanceSearch = advanceSearch or STORR
        if (mSto.isChecked) advanceSearch = advanceSearch or STO
        if (mSdt1.isChecked) advanceSearch = advanceSearch or SDT1
        if (mSdt2.isChecked) advanceSearch = advanceSearch or SDT2
        if (mSh.isChecked) advanceSearch = advanceSearch or SH
        if (mSfl.isChecked) advanceSearch = advanceSearch or SFL
        if (mSfu.isChecked) advanceSearch = advanceSearch or SFU
        if (mSft.isChecked) advanceSearch = advanceSearch or SFT
        return advanceSearch
    }

    fun getMinRating(): Int {
        val position = mMinRating.selectedItemPosition
        return if (mSr.isChecked && position >= 0) {
            position + 2
        } else {
            -1
        }
    }

    fun getPageFrom(): Int {
        if (mSp.isChecked) {
            return NumberUtils.parseIntSafely(mSpf.text.toString(), -1)
        }
        return -1
    }

    fun getPageTo(): Int {
        if (mSp.isChecked) {
            return NumberUtils.parseIntSafely(mSpt.text.toString(), -1)
        }
        return -1
    }

    fun setAdvanceSearch(advanceSearch: Int) {
        mSname.isChecked = NumberUtils.int2boolean(advanceSearch and SNAME)
        mStags.isChecked = NumberUtils.int2boolean(advanceSearch and STAGS)
        mSdesc.isChecked = NumberUtils.int2boolean(advanceSearch and SDESC)
        mStorr.isChecked = NumberUtils.int2boolean(advanceSearch and STORR)
        mSto.isChecked = NumberUtils.int2boolean(advanceSearch and STO)
        mSdt1.isChecked = NumberUtils.int2boolean(advanceSearch and SDT1)
        mSdt2.isChecked = NumberUtils.int2boolean(advanceSearch and SDT2)
        mSh.isChecked = NumberUtils.int2boolean(advanceSearch and SH)
        mSfl.isChecked = NumberUtils.int2boolean(advanceSearch and SFL)
        mSfu.isChecked = NumberUtils.int2boolean(advanceSearch and SFU)
        mSft.isChecked = NumberUtils.int2boolean(advanceSearch and SFT)
    }

    fun setMinRating(minRating: Int) {
        if (minRating in 2..5) {
            mSr.isChecked = true
            mMinRating.setSelection(minRating - 2)
        } else {
            mSr.isChecked = false
        }
    }

    fun setPageFrom(pageFrom: Int) {
        if (pageFrom > 0) {
            mSpf.setText(pageFrom.toString())
            mSp.isChecked = true
        } else {
            mSp.isChecked = false
            mSpf.setText(null)
        }
    }

    fun setPageTo(pageTo: Int) {
        if (pageTo > 0) {
            mSpt.setText(pageTo.toString())
            mSp.isChecked = true
        } else {
            mSp.isChecked = false
            mSpt.setText(null)
        }
    }

    override fun onSaveInstanceState(): Parcelable {
        val state = Bundle()
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState())
        state.putInt(STATE_KEY_ADVANCE_SEARCH, getAdvanceSearch())
        state.putInt(STATE_KEY_MIN_RATING, getMinRating())
        state.putInt(STATE_KEY_PAGE_FROM, getPageFrom())
        state.putInt(STATE_KEY_PAGE_TO, getPageTo())
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state is Bundle) {
            @Suppress("DEPRECATION")
            super.onRestoreInstanceState(state.getParcelable(STATE_KEY_SUPER))
            setAdvanceSearch(state.getInt(STATE_KEY_ADVANCE_SEARCH))
            setMinRating(state.getInt(STATE_KEY_MIN_RATING))
            setPageFrom(state.getInt(STATE_KEY_PAGE_FROM))
            setPageTo(state.getInt(STATE_KEY_PAGE_TO))
        }
    }

    companion object {
        private const val STATE_KEY_SUPER = "super"
        private const val STATE_KEY_ADVANCE_SEARCH = "advance_search"
        private const val STATE_KEY_MIN_RATING = "min_rating"
        private const val STATE_KEY_PAGE_FROM = "page_from"
        private const val STATE_KEY_PAGE_TO = "page_to"

        const val SNAME = 0x1
        const val STAGS = 0x2
        const val SDESC = 0x4
        const val STORR = 0x8
        const val STO = 0x10
        const val SDT1 = 0x20
        const val SDT2 = 0x40
        const val SH = 0x80
        const val SFL = 0x100
        const val SFU = 0x200
        const val SFT = 0x400
    }
}
