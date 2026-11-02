package com.lanraragi.reader

import android.app.ActivityManager
import android.util.Log
import com.lanraragi.reader.util.MemoryTrim
import com.lanraragi.reader.util.OSUtils

/**
 * Memory facts of the running device that need the Application. They live in
 * the composition root because `util` is a leaf (rule D2) and the framework may
 * only import `util`; the framework's reader cache takes its budget from
 * [readerCacheBytes] through its app-side subclass.
 */
object DeviceMemory {

    private const val TAG = "DeviceMemory"

    /** ActivityManager.isLowRamDevice for the running app; false before the app exists (tests). */
    fun isLowRamDevice(): Boolean = try {
        LRReaderApplication.instance
            .getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    } catch (e: UninitializedPropertyAccessException) {
        Log.w(TAG, "No application yet; assuming a normal-RAM device", e)
        false
    }

    /** Budget of the reader's decoded-page cache on this device. */
    fun readerCacheBytes(): Int = MemoryTrim.readerCacheBytes(OSUtils.getTotalMemory(), isLowRamDevice())
}
