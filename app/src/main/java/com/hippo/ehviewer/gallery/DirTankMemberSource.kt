package com.hippo.ehviewer.gallery

import android.content.Context
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.lib.image.Image
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.IOUtils
import com.hippo.unifile.UniFile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Locally-downloaded member source for the tank composite reader: pages
 * come straight from the member's download directory. Only COMPLETE local
 * copies are routed here (see [TankGalleryProvider]'s per-member routing),
 * so unlike [DirGalleryProvider] there is no gap/expectedPageCount
 * handling — the numeric map, when present, is only used to keep page
 * order faithful to real page numbers.
 */
internal class DirTankMemberSource(
    context: Context,
    override val arcid: String,
    private val dir: UniFile,
) : TankMemberSource {

    private val appContext = context.applicationContext

    @Volatile
    private var files: Array<UniFile>? = null

    @Volatile
    private var pageIndexMap: Map<Int, Int>? = null

    private val listMutex = Mutex()

    override fun knownPageCount(): Int? = files?.size

    override suspend fun ensurePageCount(): Int {
        files?.let { return it.size }
        listMutex.withLock {
            files?.let { return it.size }
            val listed = withContext(Dispatchers.IO) { DirImageFiles.listSorted(dir) }
                ?: throw IOException("Cannot enumerate download dir for $arcid")
            files = listed
            pageIndexMap = DirImageFiles.numericPageIndices(listed.map { it.name ?: "" })
            return listed.size
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
