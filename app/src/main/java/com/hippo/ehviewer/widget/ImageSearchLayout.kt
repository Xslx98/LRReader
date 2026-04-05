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

package com.hippo.ehviewer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.view.AbsSavedState
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import com.hippo.ehviewer.AppConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.io.UniFileInputStreamPipe
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.lib.yorozuya.ViewUtils
import com.hippo.unifile.UniFile
import com.hippo.util.BitmapUtils
import com.hippo.util.FileUtils
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

class ImageSearchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), View.OnClickListener {

    private val mPreview: ImageView
    private val mSelectImage: View
    private val mSearchUSS: CheckBox
    private val mSearchOSC: CheckBox
    private val mSearchSE: CheckBox

    private var mHelper: Helper? = null

    private var mImagePath: String? = null

    init {
        orientation = VERTICAL
        dividerDrawable = androidx.appcompat.content.res.AppCompatResources
            .getDrawable(context, R.drawable.spacer_keyline)
        showDividers = SHOW_DIVIDER_MIDDLE
        LayoutInflater.from(context).inflate(R.layout.widget_image_search, this)

        mPreview = ViewUtils.`$$`(this, R.id.preview) as ImageView
        mSelectImage = ViewUtils.`$$`(this, R.id.select_image)
        mSearchUSS = ViewUtils.`$$`(this, R.id.search_uss) as CheckBox
        mSearchOSC = ViewUtils.`$$`(this, R.id.search_osc) as CheckBox
        mSearchSE = ViewUtils.`$$`(this, R.id.search_se) as CheckBox

        mSelectImage.setOnClickListener(this)
    }

    fun setHelper(helper: Helper?) {
        mHelper = helper
    }

    override fun onClick(v: View) {
        if (v === mSelectImage) {
            mHelper?.onSelectImage()
        }
    }

    fun setImageUri(imageUri: Uri?) {
        if (imageUri == null) return

        val ctx = context
        val file = UniFile.fromUri(ctx, imageUri) ?: return
        try {
            val maxSize = ctx.resources.getDimensionPixelOffset(R.dimen.image_search_max_size)
            val bitmap = BitmapUtils.decodeStream(
                UniFileInputStreamPipe(file), maxSize, maxSize
            ) ?: return
            val path = FileUtils.getPath(ctx, imageUri)
            val temp = AppConfig.createTempFile() ?: return

            // EH-LEGACY: image search quality on ehentai is poor by design
            // Re-compress image will make image search failed.
            var os: FileOutputStream? = null
            try {
                os = FileOutputStream(temp)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os)
                mImagePath = if (path.isNullOrEmpty()) {
                    temp.path
                } else {
                    path
                }
                mPreview.setImageBitmap(bitmap)
                mPreview.visibility = VISIBLE
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } finally {
                IOUtils.closeQuietly(os)
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory / URI syntax")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Out of memory / URI syntax")
        }
    }

    private fun setImagePath(imagePath: String?) {
        if (imagePath == null) return

        var inputStream: FileInputStream? = null
        try {
            inputStream = FileInputStream(imagePath)
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return
            mImagePath = imagePath
            mPreview.setImageBitmap(bitmap)
            mPreview.visibility = VISIBLE
        } catch (e: FileNotFoundException) {
            // Ignore
        } finally {
            IOUtils.closeQuietly(inputStream)
        }
    }

    @Throws(EhException::class)
    fun formatListUrlBuilder(builder: ListUrlBuilder) {
        if (mImagePath == null) {
            throw EhException(context.getString(R.string.select_image_first))
        }
        builder.imagePath = mImagePath
        builder.setUseSimilarityScan(mSearchUSS.isChecked)
        builder.setOnlySearchCovers(mSearchOSC.isChecked)
        builder.setShowExpunged(mSearchSE.isChecked)
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val ss = SavedState(superState)
        ss.imagePath = mImagePath
        return ss
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        val ss = state as SavedState
        super.onRestoreInstanceState(ss.superState)
        setImagePath(ss.imagePath)
    }

    private class SavedState : AbsSavedState {

        var imagePath: String? = null

        constructor(superState: Parcelable?) : super(superState!!)

        constructor(source: Parcel) : super(source) {
            imagePath = source.readString()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeString(imagePath)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    interface Helper {
        fun onSelectImage()
    }

    companion object {
        private val TAG = ImageSearchLayout::class.java.simpleName
    }
}
