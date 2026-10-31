package com.lanraragi.reader.storage

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A file or directory in download storage (the download root, its title
 * directories and their pages), backed by [java.io.File].
 *
 * Downloads have only ever been written through `java.io.File` (the SAF
 * download-location picker was removed before v1.16.2), so this layer has no
 * document-provider backend: a `content://` uri does not resolve (see
 * [fromUri]) and callers fall back to the current download root.
 *
 * Stored formats are those of the unifile layer it replaces: [uri] is
 * `Uri.fromFile(file)`, and a title directory is recorded by its [name].
 */
class StorageFile(file: File) {

    /** The backing file. Changes only through [renameTo]. */
    var file: File = file
        private set

    /** `file://` uri of [file], as stored in `DOWNLOAD_ROOT_URI`. */
    val uri: Uri get() = Uri.fromFile(file)

    val name: String get() = file.name

    val isDirectory: Boolean get() = file.isDirectory

    val isFile: Boolean get() = file.isFile

    fun exists(): Boolean = file.exists()

    fun length(): Long = file.length()

    /** The children, or null when this is not a readable directory. */
    fun listFiles(): Array<StorageFile>? = file.listFiles()?.map(::StorageFile)?.toTypedArray()

    /** The children whose name passes [filter], or null when this is not a readable directory. */
    fun listFiles(filter: (name: String) -> Boolean): Array<StorageFile>? =
        file.listFiles()?.filter { filter(it.name) }?.map(::StorageFile)?.toTypedArray()

    /** The existing child [name], or null. */
    fun findFile(name: String): StorageFile? = File(file, name).takeIf { it.exists() }?.let(::StorageFile)

    /** The child [name], whether it exists or not. */
    fun subFile(name: String): StorageFile = StorageFile(File(file, name))

    /**
     * The child file [name], created empty when missing; null when it cannot be
     * created or a directory of that name is in the way.
     */
    fun createFile(name: String): StorageFile? {
        val target = File(file, name)
        if (target.exists()) return if (target.isFile) StorageFile(target) else null
        return try {
            FileOutputStream(target).close()
            StorageFile(target)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to create $name: $e")
            null
        }
    }

    /** The child directory [name], created with any missing parents; null on failure. */
    fun createDirectory(name: String): StorageFile? {
        val target = File(file, name)
        return if (target.isDirectory || target.mkdirs()) StorageFile(target) else null
    }

    /** Make this a directory (with missing parents); true when it is one afterwards. */
    fun ensureDir(): Boolean = file.isDirectory || file.mkdirs()

    @Throws(IOException::class)
    fun openInputStream(): InputStream = FileInputStream(file)

    @Throws(IOException::class)
    fun openOutputStream(): OutputStream = FileOutputStream(file)

    /** Delete this file, or this directory with everything under it. */
    fun delete(): Boolean {
        deleteContents(file)
        return file.delete()
    }

    /** Rename within the same directory; on success this object points at the new name. */
    fun renameTo(name: String): Boolean {
        val target = File(file.parentFile, name)
        if (!file.renameTo(target)) return false
        file = target
        return true
    }

    override fun toString(): String = file.toString()

    companion object {
        private const val TAG = "StorageFile"

        /** The file a `file://` [uri] names; null for any other scheme (a legacy `content://` root). */
        @JvmStatic
        fun fromUri(uri: Uri?): StorageFile? {
            if (!isFileUri(uri)) return null
            val path = uri?.path ?: return null
            return StorageFile(File(path))
        }

        private fun isFileUri(uri: Uri?): Boolean = uri?.scheme == ContentResolver.SCHEME_FILE

        private fun deleteContents(dir: File): Boolean {
            var success = true
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) success = deleteContents(child) && success
                if (!child.delete()) {
                    Log.w(TAG, "Failed to delete $child")
                    success = false
                }
            }
            return success
        }
    }
}
