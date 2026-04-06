package com.hippo.ehviewer.ui.scene.gallery.list

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hippo.app.EditTextDialogBuilder
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.widget.JumpDateSelector
import com.hippo.util.AppHelper
import com.hippo.widget.ContentLayout

/**
 * Handles go-to / page-jump dialogs for GalleryListScene.
 * Extracted to reduce GalleryListScene's line count.
 */
class GalleryGoToHelper(private val callback: Callback) {

    interface Callback {
        fun getHostContext(): Context?
        fun getContentHelper(): ContentLayout.ContentHelper<*>?
        fun getUrlBuilder(): ListUrlBuilder?
        fun getLayoutInflater(): LayoutInflater
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String
    }

    private var jumpSelectorDialog: AlertDialog? = null
    private var mJumpDateSelector: JumpDateSelector? = null

    fun showGoToDialog() {
        val context = callback.getHostContext() ?: return
        val helper = callback.getContentHelper() ?: return

        if (helper.mPages < 0) {
            showDateJumpDialog(context)
        } else {
            showPageJumpDialog(context)
        }
    }

    private fun showDateJumpDialog(context: Context) {
        val helper = callback.getContentHelper() ?: return
        if (helper.nextHref == null || helper.nextHref.isEmpty()) {
            Toast.makeText(context, R.string.gallery_list_no_more_data, Toast.LENGTH_LONG).show()
            return
        }
        if (jumpSelectorDialog == null) {
            val linearLayout = callback.getLayoutInflater()
                .inflate(R.layout.gallery_list_date_jump_dialog, null) as LinearLayout
            mJumpDateSelector = linearLayout.findViewById(R.id.gallery_list_jump_date)
            mJumpDateSelector!!.setOnTimeSelectedListener { urlAppend -> onTimeSelected(urlAppend) }
            jumpSelectorDialog = AlertDialog.Builder(context).setView(linearLayout).create()
        }
        mJumpDateSelector!!.setFoundMessage(helper.resultCount)
        jumpSelectorDialog!!.show()
    }

    private fun showPageJumpDialog(context: Context) {
        val helper = callback.getContentHelper() ?: return
        val page = helper.pageForTop
        val pages = helper.pages
        val hint = callback.getString(R.string.go_to_hint, page + 1, pages)
        val builder = EditTextDialogBuilder(context, null, hint)
        builder.editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val dialog = builder.setTitle(R.string.go_to)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val currentHelper = callback.getContentHelper()
            if (currentHelper == null) {
                dialog.dismiss()
                return@setOnClickListener
            }

            val text = builder.text.trim()
            val goTo: Int
            try {
                goTo = text.toInt() - 1
            } catch (e: NumberFormatException) {
                builder.setError(callback.getString(R.string.error_invalid_number))
                return@setOnClickListener
            }
            if (goTo < 0 || goTo >= pages) {
                builder.setError(callback.getString(R.string.error_out_of_range))
                return@setOnClickListener
            }
            builder.setError(null)
            currentHelper.goTo(goTo)
            AppHelper.hideSoftInput(dialog)
            dialog.dismiss()
        }
    }

    private fun onTimeSelected(urlAppend: String) {
        Log.d(TAG, urlAppend)
        val helper = callback.getContentHelper()
        val urlBuilder = callback.getUrlBuilder()
        if (urlAppend.isEmpty() || helper == null || jumpSelectorDialog == null || urlBuilder == null) {
            return
        }
        jumpSelectorDialog!!.dismiss()
        helper.nextHref = urlBuilder.jumpHrefBuild(helper.nextHref, urlAppend)
        helper.goTo(-996)
    }

    companion object {
        private const val TAG = "GalleryGoToHelper"
    }
}
