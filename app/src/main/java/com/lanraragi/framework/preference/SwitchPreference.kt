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

    /** Text on the switch widget in the on state; a very short string, one word if possible. */
    var switchTextOn: CharSequence? = null
        set(value) {
            field = value
            notifyChanged()
        }

    /** Text on the switch widget in the off state; a very short string, one word if possible. */
    var switchTextOff: CharSequence? = null
        set(value) {
            field = value
            notifyChanged()
        }

    private val checkedListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
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
        switchTextOn = a.getString(R.styleable.SwitchPreference_switchTextOn)
        switchTextOff = a.getString(R.styleable.SwitchPreference_switchTextOff)
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
                checkableView.textOn = switchTextOn
                checkableView.textOff = switchTextOff
                checkableView.setOnCheckedChangeListener(checkedListener)
            }
        }
    }

    fun setSwitchTextOn(@StringRes resId: Int) {
        switchTextOn = context.getString(resId)
    }

    fun setSwitchTextOff(@StringRes resId: Int) {
        switchTextOff = context.getString(resId)
    }
}
