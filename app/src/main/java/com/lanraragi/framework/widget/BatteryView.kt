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
import androidx.core.content.withStyledAttributes
import com.lanraragi.framework.drawable.BatteryDrawable
import com.lanraragi.reader.R

class BatteryView : AppCompatTextView {

    private var color = 0
    private var warningColor = 0
    private var currentColor = 0

    private var level = 0
    private var charging = false

    private lateinit var drawable: BatteryDrawable

    private var attached = false
    private var isChargerWorking = false

    private val intentReceiver = object : BroadcastReceiver() {

        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context, intent: Intent) {
            val newLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) *
                100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
            val newCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                BatteryManager.BATTERY_STATUS_CHARGING

            if (level != newLevel || charging != newCharging) {
                level = newLevel
                charging = newCharging
                if (charging && level != 100) {
                    startCharger()
                } else {
                    stopCharger()
                    drawable.setElect(level)
                }

                if (newLevel <= BatteryDrawable.WARN_LIMIT && !newCharging) {
                    setTextColor(warningColor)
                } else {
                    setTextColor(color)
                }
                text = "$level%"
            }
        }
    }

    private val charger: Runnable = object : Runnable {

        private var shownLevel = 0

        override fun run() {
            shownLevel += 2
            if (shownLevel > 100) {
                shownLevel = 0
            }
            drawable.setElect(shownLevel, false)
            handler.postDelayed(this, 200)
        }
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()

        context.withStyledAttributes(attrs, R.styleable.BatteryView, defStyleAttr, 0) {
            color = getColor(R.styleable.BatteryView_color, Color.WHITE)
            warningColor = getColor(R.styleable.BatteryView_warningColor, Color.RED)
        }

        drawable.setColor(color)
        drawable.setWarningColor(warningColor)
    }

    private fun init() {
        drawable = BatteryDrawable()
        val height = textSize.toInt()
        drawable.setBounds(0, 0, (height / 0.618f).toInt(), height)
        setCompoundDrawables(drawable, null, null, null)
    }

    override fun setTextColor(color: Int) {
        if (currentColor == color) {
            return
        }
        currentColor = color
        super.setTextColor(color)
    }

    private fun startCharger() {
        if (!isChargerWorking) {
            handler.post(charger)
            isChargerWorking = true
        }
    }

    private fun stopCharger() {
        if (isChargerWorking) {
            handler.removeCallbacks(charger)
            isChargerWorking = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        if (!attached) {
            attached = true

            registerReceiver()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        if (attached) {
            unregisterReceiver()
            stopCharger()
            attached = false
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(intentReceiver, filter, null, handler)
    }

    private fun unregisterReceiver() {
        context.unregisterReceiver(intentReceiver)
    }
}
