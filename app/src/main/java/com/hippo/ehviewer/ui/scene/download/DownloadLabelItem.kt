package com.hippo.ehviewer.ui.scene.download

class DownloadLabelItem @JvmOverloads constructor(
    @JvmField var label: String? = null,
    private val count: Long = 0
) {
    fun count(): String = count.toString()
}
