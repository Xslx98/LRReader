package com.hippo.ehviewer.callBack

import java.io.File

fun interface ImageChangeCallBack {
    fun backgroundSourceChange(file: File?)
}
