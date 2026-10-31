/*
 * Copyright (C) 2014 Hippo Seven
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

package com.lanraragi.framework.widget

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.lanraragi.framework.drawable.BatteryDrawable
import com.lanraragi.reader.R

class BatteryView : AppCompatTextView {

    private var mColor = 0
    private var mWarningColor = 0
    private var mCurrentColor = 0

    private var mLevel = 0
    private var mCharging = false

    private lateinit var mDrawable: BatteryDrawable

    private var mAttached = false
    private var mIsChargerWorking = false

    private val mIntentReceiver = object : BroadcastReceiver() {

        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) *
                100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
            val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                BatteryManager.BATTERY_STATUS_CHARGING

            if (mLevel != level || mCharging != charging) {
                mLevel = level
                mCharging = charging
                if (mCharging && mLevel != 100) {
                    startCharger()
                } else {
                    stopCharger()
                    mDrawable.setElect(mLevel)
                }

                if (level <= BatteryDrawable.WARN_LIMIT && !charging) {
                    setTextColor(mWarningColor)
                } else {
                    setTextColor(mColor)
                }
                text = "$mLevel%"
            }
        }
    }

    private val mCharger: Runnable = object : Runnable {

        private var level = 0

        override fun run() {
            level += 2
            if (level > 100) {
                level = 0
            }
            mDrawable.setElect(level, false)
            handler.postDelayed(this, 200)
        }
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()

        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.BatteryView, defStyleAttr, 0)
        mColor = typedArray.getColor(R.styleable.BatteryView_color, Color.WHITE)
        mWarningColor = typedArray.getColor(R.styleable.BatteryView_warningColor, Color.RED)
        typedArray.recycle()

        mDrawable.setColor(mColor)
        mDrawable.setWarningColor(mWarningColor)
    }

    private fun init() {
        mDrawable = BatteryDrawable()
        val height = textSize.toInt()
        mDrawable.setBounds(0, 0, (height / 0.618f).toInt(), height)
        setCompoundDrawables(mDrawable, null, null, null)
    }

    override fun setTextColor(color: Int) {
        if (mCurrentColor == color) {
            return
        }
        mCurrentColor = color
        super.setTextColor(color)
    }

    private fun startCharger() {
        if (!mIsChargerWorking) {
            handler.post(mCharger)
            mIsChargerWorking = true
        }
    }

    private fun stopCharger() {
        if (mIsChargerWorking) {
            handler.removeCallbacks(mCharger)
            mIsChargerWorking = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!mAttached) {
            mAttached = true

            registerReceiver()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (mAttached) {
            unregisterReceiver()
            stopCharger()
            mAttached = false
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(mIntentReceiver, filter, null, handler)
    }

    private fun unregisterReceiver() {
        context.unregisterReceiver(mIntentReceiver)
    }
}
