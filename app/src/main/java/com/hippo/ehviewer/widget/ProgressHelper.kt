package com.hippo.ehviewer.widget

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.hippo.ehviewer.R

object ProgressHelper {

    private var dialog: AlertDialog? = null
    private var tvText: TextView? = null
    private var progressMessage: String = ""

    @JvmStatic
    fun showDialog(context: Context, message: String) {
        if (dialog == null) {
            val llPadding = 30
            val ll = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(llPadding, llPadding, llPadding, llPadding)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
            }

            val llParam = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val progressBar = ProgressBar(context).apply {
                isIndeterminate = true
                setPadding(0, 0, llPadding, 0)
                layoutParams = llParam
            }

            val textParam = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            tvText = TextView(context).apply {
                progressMessage = message
                text = progressMessage
                setTextColor(context.getColor(R.color.primary_drawable_light))
                textSize = 20f
                layoutParams = textParam
            }

            ll.addView(progressBar)
            ll.addView(tvText)

            val builder = AlertDialog.Builder(context)
            builder.setCancelable(false)
            builder.setView(ll)

            dialog = builder.create()
            dialog!!.show()
            val window = dialog!!.window
            if (window != null) {
                val layoutParams = WindowManager.LayoutParams()
                layoutParams.copyFrom(window.attributes)
                layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
                layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
                window.attributes = layoutParams
            }
        }
    }

    @JvmStatic
    fun setProgress(progress: Int): Boolean {
        val tv = tvText ?: return false
        val text = "$progress% $progressMessage"
        tv.text = text
        return true
    }

    @JvmStatic
    fun isDialogVisible(): Boolean {
        return dialog?.isShowing ?: false
    }

    @JvmStatic
    fun dismissDialog() {
        dialog?.dismiss()
        dialog = null
        tvText = null
    }
}
