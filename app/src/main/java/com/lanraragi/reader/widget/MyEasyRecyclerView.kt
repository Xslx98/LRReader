package com.lanraragi.reader.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import com.lanraragi.reader.widget.list.EasyRecyclerView

class MyEasyRecyclerView : EasyRecyclerView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        try {
            return super.onTouchEvent(e)
        } catch (e: IllegalArgumentException) {
            Log.e("RecyclerView", "IllegalArgumentException", e)
        }
        return true
    }
}
