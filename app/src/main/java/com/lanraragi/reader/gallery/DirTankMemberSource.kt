package com.lanraragi.reader.gallery

import android.content.Context
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.framework.lib.image.Image
import com.lanraragi.framework.lib.yorozuya.FileUtils
import com.lanraragi.framework.lib.yorozuya.IOUtils
import com.lanraragi.framework.unifile.UniFile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Locally-downloaded member source for the tank composite reader: pages
 * come straight from the member's download directory. Complete local
 * copies and — offline — partial ones are routed here (see
 * [TankMemberRouting]). The numeric map keeps page order faithful to real
 * page numbers, so a middle gap is an explicit missing page; a trailing
 * gap is only visible when [expectedPageCount] says the archive is longer
 * than the directory ([DirImageFiles.pageSpaceSize]) — routing passes it
 * for a known-partial copy and 0 for a complete one.
 */
internal class DirTankMemberSource(
    context: Context,
    override val arcid: String,
    private val dir: UniFile,
    /** Server pagecount for a known-partial dir (missing tail = error pages); 0 = trust the dir. */
    val expectedPageCount: Int = 0,
) : TankMemberSource {

    private val appContext = context.applicationContext

    @Volatile
    private var files: Array<UniFile>? = null

    @Volatile
    private var pageIndexMap: Map<Int, Int>? = null

    /** Pages the reader exposes; may exceed the file count for a partial dir. */
    @Volatile
    private var pageSpace: Int? = null

    private val listMutex = Mutex()

    override fun knownPageCount(): Int? = pageSpace

    override suspend fun ensurePageCount(): Int {
        pageSpace?.let { return it }
        listMutex.withLock {
            pageSpace?.let { return it }
            val listed = withContext(Dispatchers.IO) { DirImageFiles.listSorted(dir) }
                ?: throw IOException("Cannot enumerate download dir for $arcid")
            val map = DirImageFiles.numericPageIndices(listed.map { it.name ?: "" })
            files = listed
            pageIndexMap = map
            return DirImageFiles.pageSpaceSize(map, listed.size, expectedPageCount)
                .also { pageSpace = it }
        }
    }

    override suspend fun obtainImage(page0: Int, onPercent: ((Float) -> Unit)?): Image? {
        val file = fileAt(page0) ?: throw IOException("Page $page0 missing in dir for $arcid")
        return withContext(ServiceRegistry.coroutineModule.decoderDispatcher) {
            DirImageFiles.decode(appContext, file)
        }
    }

    override suspend fun prefetchPage(page0: Int) {
        // Local files need no warm-up.
    }

    override fun savePage(page0: Int, dest: UniFile): Boolean {
        val src = fileAt(page0) ?: return false
        return try {
            src.openInputStream().use { input ->
                dest.openOutputStream().use { output -> IOUtils.copy(input, output) }
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Extension of the underlying file for [page0] (save path), or null. */
    fun pageExtension(page0: Int): String? =
        fileAt(page0)?.name?.let { FileUtils.getExtensionFromFilename(it) }

    override fun cancelPage(page0: Int) {
        // Nothing in flight for local files.
    }

    override fun cancelAll() {
        // Nothing in flight for local files.
    }

    /**
     * File for a 0-indexed local page: through the numeric map when the
     * dir uses worker naming (order faithful to page numbers), else
     * positional. Sorted position i for a complete numeric dir maps to
     * page i anyway; the map guards odd numeric namings.
     */
    private fun fileAt(page0: Int): UniFile? {
        val listed = files ?: return null
        val map = pageIndexMap ?: return listed.getOrNull(page0)
        val pos = map[page0] ?: return null
        return listed.getOrNull(pos)
    }
}
