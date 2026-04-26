package com.hippo.ehviewer.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.util.ExceptionUtils

object ClipboardUtil {

    /**
     * Copy plain text to the system clipboard.
     */
    @JvmStatic
    fun copyText(text: String?) {
        clearClipboard()
        if (!TextUtils.isEmpty(text)) {
            val cmb = ServiceRegistry.appModule.getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(null, text)
            cmb.setPrimaryClip(clipData)
        }
    }

    private fun clearClipboard() {
        val manager = ServiceRegistry.appModule.getContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager != null) {
            try {
                manager.setPrimaryClip(manager.primaryClip!!)
                @Suppress("DEPRECATION")
                manager.text = null
            } catch (e: Exception) {
                ExceptionUtils.getReadableString(e)
            }
        }
    }
}
