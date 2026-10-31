/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.lanraragi.framework.preference

import android.content.Context
import android.content.res.TypedArray
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.widget.SeekBar
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.lanraragi.reader.R

class SeekBarPreference : Preference, SeekBar.OnSeekBarChangeListener {

    private var progressValue = 0
    private var maxValue = 0
    private var trackingTouch = false

    /** The upper bound of [progress]. */
    var max: Int
        get() = maxValue
        set(value) {
            if (value != maxValue) {
                maxValue = value
                notifyChanged()
            }
        }

    /** The value, kept within 0..[max] and persisted. */
    var progress: Int
        get() = progressValue
        set(value) {
            updateProgress(value, notifyChanged = true)
        }

    constructor(context: Context) : super(context) {
        init(context, null, 0, 0)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs, 0, 0)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs, defStyleAttr, 0)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.SeekBarPreference, defStyleAttr, defStyleRes)
        max = a.getInt(R.styleable.SeekBarPreference_max, maxValue)
        a.recycle()

        layoutResource = R.layout.preference_widget_seekbar
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val seekBar = holder.findViewById(R.id.seekbar) as SeekBar
        seekBar.setOnSeekBarChangeListener(this)
        seekBar.max = maxValue
        seekBar.progress = progressValue
        seekBar.isEnabled = isEnabled
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        progress = getPersistedInt(progressValue)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getInt(index, 0)

    private fun updateProgress(value: Int, notifyChanged: Boolean) {
        val clamped = value.coerceAtMost(maxValue).coerceAtLeast(0)
        if (clamped != progressValue) {
            progressValue = clamped
            persistInt(clamped)
            if (notifyChanged) {
                notifyChanged()
            }
        }
    }

    /**
     * Persist the seekBar's progress value if callChangeListener
     * returns true, otherwise set the seekBar's progress to the stored value
     */
    private fun syncProgress(seekBar: SeekBar) {
        val value = seekBar.progress
        if (value != progressValue) {
            if (callChangeListener(value)) {
                updateProgress(value, notifyChanged = false)
            } else {
                seekBar.progress = progressValue
            }
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser && !trackingTouch) {
            syncProgress(seekBar)
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        trackingTouch = true
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        trackingTouch = false
        if (seekBar.progress != progressValue) {
            syncProgress(seekBar)
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        // Suppose a client uses this preference type without persisting. We
        // must save the instance state so it is able to, for example, survive
        // orientation changes.
        val superState = super.onSaveInstanceState()
        if (isPersistent) {
            // No need to save instance state since it's persistent
            return superState
        }

        return SavedState(superState).apply {
            progress = progressValue
            max = maxValue
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState || state.javaClass != SavedState::class.java) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state)
            return
        }

        super.onRestoreInstanceState(state.superState)
        progressValue = state.progress
        maxValue = state.max
        notifyChanged()
    }

    /** Stores the progress and max of a preference that does not persist them. */
    private class SavedState : BaseSavedState {
        var progress = 0
        var max = 0

        constructor(source: Parcel) : super(source) {
            progress = source.readInt()
            max = source.readInt()
        }

        constructor(superState: Parcelable?) : super(superState)

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeInt(progress)
            dest.writeInt(max)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)

                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }
}
