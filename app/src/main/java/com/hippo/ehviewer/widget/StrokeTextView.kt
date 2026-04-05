package com.hippo.ehviewer.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.TextView
import com.hippo.ehviewer.R

@SuppressLint("AppCompatCustomView")
class StrokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private val outlineTextView: TextView = TextView(context, attrs, defStyleAttr)

    init {
        // 1.获取参数
        val ta = context.obtainStyledAttributes(attrs, R.styleable.StrokeTextView)
        val strokeColor = ta.getColor(R.styleable.StrokeTextView_stroke_color, Color.WHITE)
        val strokeWidth = ta.getDimension(R.styleable.StrokeTextView_stroke_width, 2f)
        ta.recycle()

        // 2.初始化TextPaint
        val paint = outlineTextView.paint
        paint.strokeWidth = strokeWidth
        paint.style = Paint.Style.STROKE
        outlineTextView.setTextColor(strokeColor)
        outlineTextView.gravity = gravity
    }

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        super.setLayoutParams(params)
        outlineTextView.layoutParams = params
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 设置轮廓文字
        val outlineText = outlineTextView.text
        if (outlineText == null || outlineText != text) {
            outlineTextView.text = text
            postInvalidate()
        }
        outlineTextView.measure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        outlineTextView.layout(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        outlineTextView.draw(canvas)
        super.onDraw(canvas)
    }
}
