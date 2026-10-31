/*
 * Copyright 2015 Hippo Seven
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
import android.util.AttributeSet
import android.widget.Checkable
import android.widget.CompoundButton
import androidx.annotation.StringRes
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import com.lanraragi.reader.R

class SwitchPreference : TwoStatePreference {

    // Switch text for on and off states
    private var mSwitchOn: CharSequence? = null
    private var mSwitchOff: CharSequence? = null

    private val mListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
        if (!callChangeListener(isChecked)) {
            // Listener didn't like it, change it back.
            // CompoundButton will make sure we don't recurse.
            buttonView.isChecked = !isChecked
        } else {
            this.isChecked = isChecked
        }
    }

    constructor(context: Context) : super(context) {
        init(context, null, 0, 0)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs, android.R.attr.preferenceStyle) {
        init(context, attrs, 0, 0)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs, defStyleAttr, 0)
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) {
        widgetLayoutResource = R.layout.preference_widget_fixed_switch
        val a = context.obtainStyledAttributes(attrs, R.styleable.SwitchPreference, defStyleAttr, defStyleRes)
        summaryOn = a.getString(R.styleable.SwitchPreference_summaryOn)
        summaryOff = a.getString(R.styleable.SwitchPreference_summaryOff)
        setSwitchTextOn(a.getString(R.styleable.SwitchPreference_switchTextOn))
        setSwitchTextOff(a.getString(R.styleable.SwitchPreference_switchTextOff))
        disableDependentsState = a.getBoolean(R.styleable.SwitchPreference_disableDependentsState, false)
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val checkableView = holder.findViewById(R.id.switchWidget)
        if (checkableView is Checkable) {
            if (checkableView is SwitchCompat) {
                checkableView.setOnCheckedChangeListener(null)
            }

            checkableView.isChecked = isChecked

            if (checkableView is SwitchCompat) {
                checkableView.textOn = mSwitchOn
                checkableView.textOff = mSwitchOff
                checkableView.setOnCheckedChangeListener(mListener)
            }
        }
    }

    /**
     * Set the text displayed on the switch widget in the on state.
     * This should be a very short string; one word if possible.
     */
    fun setSwitchTextOn(onText: CharSequence?) {
        mSwitchOn = onText
        notifyChanged()
    }

    /**
     * Set the text displayed on the switch widget in the off state.
     * This should be a very short string; one word if possible.
     */
    fun setSwitchTextOff(offText: CharSequence?) {
        mSwitchOff = offText
        notifyChanged()
    }

    fun setSwitchTextOn(@StringRes resId: Int) {
        setSwitchTextOn(context.getString(resId))
    }

    fun setSwitchTextOff(@StringRes resId: Int) {
        setSwitchTextOff(context.getString(resId))
    }

    /** The text that will be displayed on the switch widget in the on state. */
    fun getSwitchTextOn(): CharSequence? = mSwitchOn

    /** The text that will be displayed on the switch widget in the off state. */
    fun getSwitchTextOff(): CharSequence? = mSwitchOff
}
