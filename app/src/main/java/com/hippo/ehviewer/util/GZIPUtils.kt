package com.hippo.ehviewer.util

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object GZIPUtils {

    /**
     * 使用gzip进行压缩
     */
    @JvmStatic
    fun compress(primStr: String?): String? {
        if (primStr.isNullOrEmpty()) {
            return primStr
        }

        val out = ByteArrayOutputStream()
        var gzip: GZIPOutputStream? = null
        try {
            gzip = GZIPOutputStream(out)
            gzip.write(primStr.toByteArray())
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (gzip != null) {
                try {
                    gzip.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        return String(Base64.encode(out.toByteArray(), Base64.DEFAULT))
    }

    @JvmStatic
    fun uncompress(compressedStr: String?): String? {
        if (compressedStr == null) {
            return null
        }

        val out = ByteArrayOutputStream()
        var inStream: ByteArrayInputStream? = null
        var ginzip: GZIPInputStream? = null
        var decompressed: String? = null
        try {
            val compressed = Base64.decode(compressedStr.toByteArray(), Base64.DEFAULT)
            inStream = ByteArrayInputStream(compressed)
            ginzip = GZIPInputStream(inStream)

            val buffer = ByteArray(1024)
            var offset: Int
            while (ginzip.read(buffer).also { offset = it } != -1) {
                out.write(buffer, 0, offset)
            }
            decompressed = out.toString()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try { ginzip?.close() } catch (_: IOException) {}
            try { inStream?.close() } catch (_: IOException) {}
            try { out.close() } catch (_: IOException) {}
        }
        return decompressed
    }

    /**
     * DeCompress the ZIP to the path
     * @param zipFileString  name of ZIP
     * @param outPathString   path to be unZIP
     */
    @JvmStatic
    @Suppress("FunctionNaming")
    fun UnZipFolder(zipFileString: String, outPathString: String): Boolean {
        return try {
            val outPathFile = File(outPathString)
            if (!outPathFile.exists()) {
                outPathFile.mkdir()
            }
            val inZip = ZipInputStream(FileInputStream(zipFileString))
            var zipEntry: ZipEntry?
            while (inZip.nextEntry.also { zipEntry = it } != null) {
                val szName = zipEntry!!.name
                if (zipEntry!!.isDirectory) {
                    // get the folder name of the widget
                    val folderName = szName.substring(0, szName.length - 1)
                    val folder = File(outPathString + File.separator + folderName)
                    folder.mkdirs()
                } else {
                    val file = File(outPathString + File.separator + szName)
                    file.createNewFile()
                    // get the output stream of the file
                    val out = FileOutputStream(file)
                    val buffer = ByteArray(1024)
                    var len: Int
                    // read (len) bytes into buffer
                    while (inZip.read(buffer).also { len = it } != -1) {
                        // write (len) byte from buffer at the position 0
                        out.write(buffer, 0, len)
                        out.flush()
                    }
                    out.close()
                }
            }
            inZip.close()
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Compress file and folder
     * @param srcFileString   file or folder to be Compress
     * @param zipFileString   the path name of result ZIP
     */
    @JvmStatic
    @Suppress("FunctionNaming")
    @Throws(Exception::class)
    fun ZipFolder(srcFileString: String, zipFileString: String) {
        //create ZIP
        val outZip = ZipOutputStream(FileOutputStream(zipFileString))
        //create the file
        val file = File(srcFileString)
        //compress
        ZipFiles(file.parent + File.separator, file.name, outZip)
        //finish and close
        outZip.finish()
        outZip.close()
    }

    /**
     * compress files
     */
    @Suppress("FunctionNaming")
    @Throws(Exception::class)
    private fun ZipFiles(folderString: String, fileString: String, zipOutputSteam: ZipOutputStream?) {
        if (zipOutputSteam == null) return
        val file = File(folderString + fileString)
        if (file.isFile) {
            val zipEntry = ZipEntry(fileString)
            val inputStream = FileInputStream(file)
            zipOutputSteam.putNextEntry(zipEntry)
            val buffer = ByteArray(4096)
            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                zipOutputSteam.write(buffer, 0, len)
            }
            zipOutputSteam.closeEntry()
        } else {
            //folder
            val fileList = file.list()
            //no child file and compress
            if (fileList == null || fileList.isEmpty()) {
                val zipEntry = ZipEntry(fileString + File.separator)
                zipOutputSteam.putNextEntry(zipEntry)
                zipOutputSteam.closeEntry()
            } else {
                //child files and recursion
                for (item in fileList) {
                    ZipFiles(folderString, fileString + File.separator + item, zipOutputSteam)
                }
            }
        }
    }

    /**
     * return the InputStream of file in the ZIP
     * @param zipFileString  name of ZIP
     * @param fileString     name of file in the ZIP
     * @return InputStream
     */
    @JvmStatic
    @Suppress("FunctionNaming")
    @Throws(Exception::class)
    fun UpZip(zipFileString: String, fileString: String): InputStream {
        val zipFile = ZipFile(zipFileString)
        val zipEntry = zipFile.getEntry(fileString)
        return zipFile.getInputStream(zipEntry)
    }

    /**
     * return files list(file and folder) in the ZIP
     * @param zipFileString     ZIP name
     * @param bContainFolder    contain folder or not
     * @param bContainFile      contain file or not
     */
    @JvmStatic
    @Suppress("FunctionNaming")
    @Throws(Exception::class)
    fun GetFileList(zipFileString: String, bContainFolder: Boolean, bContainFile: Boolean): List<File> {
        val fileList = mutableListOf<File>()
        val inZip = ZipInputStream(FileInputStream(zipFileString))
        var zipEntry: ZipEntry?
        while (inZip.nextEntry.also { zipEntry = it } != null) {
            val szName = zipEntry!!.name
            if (zipEntry!!.isDirectory) {
                // get the folder name of the widget
                val folderName = szName.substring(0, szName.length - 1)
                val folder = File(folderName)
                if (bContainFolder) {
                    fileList.add(folder)
                }
            } else {
                val file = File(szName)
                if (bContainFile) {
                    fileList.add(file)
                }
            }
        }
        inZip.close()
        return fileList
    }
}
