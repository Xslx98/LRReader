package com.lanraragi.reader.storage

import android.util.Log
import java.io.IOException

/** Adds or removes the `.nomedia` marker that keeps a directory out of the media scanner. */
object NoMediaFiles {

    private const val TAG = "NoMediaFiles"

    @JvmStatic
    fun ensure(dir: StorageFile?) {
        if (dir == null) return

        val noMedia = dir.createFile(".nomedia") ?: return

        try {
            noMedia.openInputStream().close()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to open .nomedia input stream", e)
        }
    }

    @JvmStatic
    fun remove(dir: StorageFile?) {
        if (dir == null) return

        val noMedia = dir.subFile(".nomedia")
        if (noMedia.isFile) {
            noMedia.delete()
        }
    }
}
