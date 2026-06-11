package com.hippo.ehviewer.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import com.hippo.ehviewer.ServiceRegistry

object ClipboardUtil {

    /**
     * Copy plain text to the system clipboard.
     */
    @JvmStatic
    fun copyText(text: String?) {
        if (!TextUtils.isEmpty(text)) {
            val cmb = ServiceRegistry.appModule.getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(null, text)
            // setPrimaryClip replaces the existing clip; never read primaryClip first —
            // a clipboard read triggers the Android 12+ "pasted from clipboard" toast.
            cmb.setPrimaryClip(clipData)
        }
    }
}
