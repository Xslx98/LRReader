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
import android.view.ViewGroup
import android.widget.TableLayout
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhConfig
import com.hippo.lib.yorozuya.NumberUtils
import com.hippo.widget.CheckTextView

class CategoryTable @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TableLayout(context, attrs), View.OnLongClickListener {

    private lateinit var mDoujinshi: CheckTextView
    private lateinit var mManga: CheckTextView
    private lateinit var mArtistCG: CheckTextView
    private lateinit var mGameCG: CheckTextView
    private lateinit var mWestern: CheckTextView
    private lateinit var mNonH: CheckTextView
    private lateinit var mImageSets: CheckTextView
    private lateinit var mCosplay: CheckTextView
    private lateinit var mAsianPorn: CheckTextView
    private lateinit var mMisc: CheckTextView

    private lateinit var mOptions: Array<CheckTextView>

    init {
        init()
    }

    fun init() {
        LayoutInflater.from(context).inflate(R.layout.widget_category_table, this)

        val row0 = getChildAt(0) as ViewGroup
        mDoujinshi = row0.getChildAt(0) as CheckTextView
        mManga = row0.getChildAt(1) as CheckTextView

        val row1 = getChildAt(1) as ViewGroup
        mArtistCG = row1.getChildAt(0) as CheckTextView
        mGameCG = row1.getChildAt(1) as CheckTextView

        val row2 = getChildAt(2) as ViewGroup
        mWestern = row2.getChildAt(0) as CheckTextView
        mNonH = row2.getChildAt(1) as CheckTextView

        val row3 = getChildAt(3) as ViewGroup
        mImageSets = row3.getChildAt(0) as CheckTextView
        mCosplay = row3.getChildAt(1) as CheckTextView

        val row4 = getChildAt(4) as ViewGroup
        mAsianPorn = row4.getChildAt(0) as CheckTextView
        mMisc = row4.getChildAt(1) as CheckTextView

        mOptions = arrayOf(
            mDoujinshi, mManga, mArtistCG, mGameCG, mWestern,
            mNonH, mImageSets, mCosplay, mAsianPorn, mMisc
        )

        for (option in mOptions) {
            option.setOnLongClickListener(this)
        }
    }

    override fun onLongClick(v: View): Boolean {
        if (v is CheckTextView) {
            val checked = v.isChecked
            for (option in mOptions) {
                if (option != v) {
                    option.setChecked(!checked, false)
                }
            }
        }
        return true
    }

    /**
     * Set each button checked or not according to category.
     */
    fun setCategory(category: Int) {
        mDoujinshi.setChecked(!NumberUtils.int2boolean(category and EhConfig.DOUJINSHI), false)
        mManga.setChecked(!NumberUtils.int2boolean(category and EhConfig.MANGA), false)
        mArtistCG.setChecked(!NumberUtils.int2boolean(category and EhConfig.ARTIST_CG), false)
        mGameCG.setChecked(!NumberUtils.int2boolean(category and EhConfig.GAME_CG), false)
        mWestern.setChecked(!NumberUtils.int2boolean(category and EhConfig.WESTERN), false)
        mNonH.setChecked(!NumberUtils.int2boolean(category and EhConfig.NON_H), false)
        mImageSets.setChecked(!NumberUtils.int2boolean(category and EhConfig.IMAGE_SET), false)
        mCosplay.setChecked(!NumberUtils.int2boolean(category and EhConfig.COSPLAY), false)
        mAsianPorn.setChecked(!NumberUtils.int2boolean(category and EhConfig.ASIAN_PORN), false)
        mMisc.setChecked(!NumberUtils.int2boolean(category and EhConfig.MISC), false)
    }

    /**
     * Get category according to button.
     */
    fun getCategory(): Int {
        var category = 0
        if (!mDoujinshi.isChecked) category = category or EhConfig.DOUJINSHI
        if (!mManga.isChecked) category = category or EhConfig.MANGA
        if (!mArtistCG.isChecked) category = category or EhConfig.ARTIST_CG
        if (!mGameCG.isChecked) category = category or EhConfig.GAME_CG
        if (!mWestern.isChecked) category = category or EhConfig.WESTERN
        if (!mNonH.isChecked) category = category or EhConfig.NON_H
        if (!mImageSets.isChecked) category = category or EhConfig.IMAGE_SET
        if (!mCosplay.isChecked) category = category or EhConfig.COSPLAY
        if (!mAsianPorn.isChecked) category = category or EhConfig.ASIAN_PORN
        if (!mMisc.isChecked) category = category or EhConfig.MISC
        return category
    }

    override fun onSaveInstanceState(): Parcelable {
        val state = Bundle()
        state.putParcelable(STATE_KEY_SUPER, super.onSaveInstanceState())
        state.putInt(STATE_KEY_CATEGORY, getCategory())
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state is Bundle) {
            @Suppress("DEPRECATION")
            super.onRestoreInstanceState(state.getParcelable(STATE_KEY_SUPER))
            setCategory(state.getInt(STATE_KEY_CATEGORY))
        }
    }

    companion object {
        private const val STATE_KEY_SUPER = "super"
        private const val STATE_KEY_CATEGORY = "category"
    }
}
