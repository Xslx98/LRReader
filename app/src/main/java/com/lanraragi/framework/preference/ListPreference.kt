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

    /**
     * The human-readable entries shown in the list of subsequent dialogs; each one
     * has a corresponding index in [entryValues].
     */
    var entries: Array<CharSequence>? = null

    /**
     * The values saved for the preference: picking the second entry saves the
     * second value.
     */
    var entryValues: Array<out Any>? = null

    /** The value of the key; one of [entryValues]. Persisted and notified on change. */
    var value: String? = null
        set(value) {
            // Always persist/notify the first time.
            val changed = !TextUtils.equals(field, value)
            if (changed || !valueSet) {
                field = value
                valueSet = true
                persistString(value)
                if (changed) {
                    notifyChanged()
                }
            }
        }

    // The XML summary, a String.format pattern that receives the current entry.
    private var summaryFormat: String? = null
    private var clickedDialogEntryIndex = 0
    private var valueSet = false

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
        entries = a.getTextArray(R.styleable.ListPreference_entries)
        entryValues = a.getTextArray(R.styleable.ListPreference_entryValues)
        a.recycle()

        // Retrieve the Preference summary attribute since it's private in the Preference class.
        a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.summary), defStyleAttr, defStyleRes)
        summaryFormat = a.getString(0)
        a.recycle()
    }

    fun setEntries(@ArrayRes entriesResId: Int) {
        entries = context.resources.getTextArray(entriesResId)
    }

    fun setEntryValues(@ArrayRes entryValuesResId: Int) {
        entryValues = context.resources.getTextArray(entryValuesResId)
    }

    /**
     * Returns the summary of this ListPreference. If the summary has a
     * [String.format] marker in it (i.e. "%s" or "%1$s"), then the current entry
     * value will be substituted in its place.
     */
    override fun getSummary(): CharSequence? {
        val summary = summaryFormat ?: return super.getSummary()
        return String.format(summary, entry ?: "")
    }

    /**
     * Sets the summary for this Preference with a CharSequence. If the summary has a
     * [String.format] marker in it (i.e. "%s" or "%1$s"), then the current entry
     * value will be substituted in its place when it's retrieved.
     */
    override fun setSummary(summary: CharSequence?) {
        super.setSummary(summary)
        if (summary == null && summaryFormat != null) {
            summaryFormat = null
        } else if (summary != null && summary != summaryFormat) {
            summaryFormat = summary.toString()
        }
    }

    /** Sets the value to the given index from the entry values. */
    fun setValueIndex(index: Int) {
        val entryValues = entryValues ?: return
        value = entryValues[index].toString()
    }

    /** The entry corresponding to the current value, or null. */
    val entry: CharSequence?
        get() {
            val index = valueIndex
            val entries = entries
            return if (index >= 0 && entries != null) entries[index] else null
        }

    /**
     * Returns the index of the given value (in the entry values array), or -1 if not found.
     */
    fun findIndexOfValue(value: String?): Int {
        val entryValues = entryValues
        if (value != null && entryValues != null) {
            for (i in entryValues.indices.reversed()) {
                if (entryValues[i] == value) {
                    return i
                }
            }
        }
        return -1
    }

    private val valueIndex: Int get() = findIndexOfValue(value)

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        val entries = entries
        check(entries != null && entryValues != null) {
            "ListPreference requires an entries array and an entryValues array."
        }

        clickedDialogEntryIndex = valueIndex
        builder.setSingleChoiceItems(entries, clickedDialogEntryIndex) { dialog, which ->
            clickedDialogEntryIndex = which

            // Clicking on an item simulates the positive button click, and dismisses the dialog.
            onClick(dialog, DialogInterface.BUTTON_POSITIVE)
            dialog.dismiss()
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val entryValues = entryValues
        if (positiveResult && clickedDialogEntryIndex >= 0 && entryValues != null) {
            val value = entryValues[clickedDialogEntryIndex].toString()
            if (callChangeListener(value)) {
                this.value = value
            }
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? = a.getString(index)

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedString(defaultValue as String?)
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        if (isPersistent) {
            // No need to save instance state since it's persistent
            return superState
        }

        val myState = SavedState(superState)
        myState.value = value
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
        value = myState.value
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
