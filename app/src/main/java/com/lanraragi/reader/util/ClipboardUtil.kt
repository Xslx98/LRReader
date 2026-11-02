package com.lanraragi.reader.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils

object ClipboardUtil {

    /**
     * Copy plain text to the system clipboard.
     */
    @JvmStatic
    fun copyText(context: Context, text: String?) {
        if (!TextUtils.isEmpty(text)) {
            val cmb = context.applicationContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(null, text)
            // setPrimaryClip replaces the existing clip; never read primaryClip first —
            // a clipboard read triggers the Android 12+ "pasted from clipboard" toast.
            cmb.setPrimaryClip(clipData)
        }
    }
}
