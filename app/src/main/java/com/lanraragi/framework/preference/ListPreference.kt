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

package com.lanraragi.framework.preference

import android.content.Context
import android.content.DialogInterface
import android.content.res.TypedArray
import android.os.Parcel
import android.os.Parcelable
import android.text.TextUtils
import android.util.AttributeSet
import androidx.annotation.ArrayRes
import androidx.appcompat.app.AlertDialog
import com.lanraragi.reader.R

class ListPreference : DialogPreference {

    private var mEntries: Array<CharSequence>? = null
    private var mEntryValues: Array<out Any>? = null
    private var mValue: String? = null
    private var mSummary: String? = null
    private var mClickedDialogEntryIndex = 0
    private var mValueSet = false

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
        setNegativeButtonText(android.R.string.cancel)

        var a = context.obtainStyledAttributes(attrs, R.styleable.ListPreference, defStyleAttr, defStyleRes)
        mEntries = a.getTextArray(R.styleable.ListPreference_entries)
        mEntryValues = a.getTextArray(R.styleable.ListPreference_entryValues)
        a.recycle()

        // Retrieve the Preference summary attribute since it's private in the Preference class.
        a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.summary), defStyleAttr, defStyleRes)
        mSummary = a.getString(0)
        a.recycle()
    }

    /**
     * Sets the human-readable entries to be shown in the list. This will be
     * shown in subsequent dialogs.
     *
     * Each entry must have a corresponding index in [setEntryValues].
     */
    fun setEntries(entries: Array<CharSequence>?) {
        mEntries = entries
    }

    fun setEntries(@ArrayRes entriesResId: Int) {
        setEntries(context.resources.getTextArray(entriesResId))
    }

    /** The list of entries to be shown in the list in subsequent dialogs. */
    fun getEntries(): Array<CharSequence>? = mEntries

    /**
     * The array to find the value to save for a preference when an entry from
     * entries is selected. If a user clicks on the second item in entries, the
     * second item in this array will be saved to the preference.
     */
    fun setEntryValues(entryValues: Array<CharSequence>?) {
        mEntryValues = entryValues
    }

    fun setEntryValues(@ArrayRes entryValuesResId: Int) {
        setEntryValues(context.resources.getTextArray(entryValuesResId))
    }

    /** Returns the array of values to be saved for the preference. */
    fun getEntryValues(): Array<out Any>? = mEntryValues

    /**
     * Sets the value of the key. This should be one of the entries in [getEntryValues].
     */
    fun setValue(value: String?) {
        // Always persist/notify the first time.
        val changed = !TextUtils.equals(mValue, value)
        if (changed || !mValueSet) {
            mValue = value
            mValueSet = true
            persistString(value)
            if (changed) {
                notifyChanged()
            }
        }
    }

    /**
     * Returns the summary of this ListPreference. If the summary has a
     * [String.format] marker in it (i.e. "%s" or "%1$s"), then the current entry
     * value will be substituted in its place.
     */
    override fun getSummary(): CharSequence? {
        val entry = getEntry()
        val summary = mSummary ?: return super.getSummary()
        return String.format(summary, entry ?: "")
    }

    /**
     * Sets the summary for this Preference with a CharSequence. If the summary has a
     * [String.format] marker in it (i.e. "%s" or "%1$s"), then the current entry
     * value will be substituted in its place when it's retrieved.
     */
    override fun setSummary(summary: CharSequence?) {
        super.setSummary(summary)
        if (summary == null && mSummary != null) {
            mSummary = null
        } else if (summary != null && summary != mSummary) {
            mSummary = summary.toString()
        }
    }

    /** Sets the value to the given index from the entry values. */
    fun setValueIndex(index: Int) {
        val entryValues = mEntryValues ?: return
        setValue(entryValues[index].toString())
    }

    /** Returns the value of the key. This should be one of the entries in [getEntryValues]. */
    fun getValue(): String? = mValue

    /** Returns the entry corresponding to the current value, or null. */
    fun getEntry(): CharSequence? {
        val index = getValueIndex()
        val entries = mEntries
        return if (index >= 0 && entries != null) entries[index] else null
    }

    /**
     * Returns the index of the given value (in the entry values array), or -1 if not found.
     */
    fun findIndexOfValue(value: String?): Int {
        val entryValues = mEntryValues
        if (value != null && entryValues != null) {
            for (i in entryValues.indices.reversed()) {
                if (entryValues[i] == value) {
                    return i
                }
            }
        }
        return -1
    }

    private fun getValueIndex(): Int = findIndexOfValue(mValue)

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        val entries = mEntries
        check(entries != null && mEntryValues != null) {
            "ListPreference requires an entries array and an entryValues array."
        }

        mClickedDialogEntryIndex = getValueIndex()
        builder.setSingleChoiceItems(entries, mClickedDialogEntryIndex) { dialog, which ->
            mClickedDialogEntryIndex = which

            // Clicking on an item simulates the positive button click, and dismisses the dialog.
            onClick(dialog, DialogInterface.BUTTON_POSITIVE)
            dialog.dismiss()
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)

        val entryValues = mEntryValues
        if (positiveResult && mClickedDialogEntryIndex >= 0 && entryValues != null) {
            val value = entryValues[mClickedDialogEntryIndex].toString()
            if (callChangeListener(value)) {
                setValue(value)
            }
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? = a.getString(index)

    override fun onSetInitialValue(defaultValue: Any?) {
        setValue(getPersistedString(defaultValue as String?))
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        if (isPersistent) {
            // No need to save instance state since it's persistent
            return superState
        }

        val myState = SavedState(superState)
        myState.value = getValue()
        return myState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state == null || state.javaClass != SavedState::class.java) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state)
            return
        }

        val myState = state as SavedState
        super.onRestoreInstanceState(myState.superState)
        setValue(myState.value)
    }

    private class SavedState : BaseSavedState {
        var value: String? = null

        constructor(source: Parcel) : super(source) {
            value = source.readString()
        }

        constructor(superState: Parcelable?) : super(superState)

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeString(value)
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
