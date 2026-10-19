package com.lanraragi.reader.ui.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.lanraragi.reader.domain.Archive
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.gallery.TankSessionSeed
import com.lanraragi.reader.ui.GalleryActivity
import com.lanraragi.reader.ui.GalleryActivity.Companion.DATA_IN_EVENT
import com.lanraragi.reader.ui.GalleryActivity.Companion.KEY_ACTION
import com.lanraragi.reader.ui.GalleryActivity.Companion.KEY_ARCHIVE
import com.lanraragi.reader.ui.GalleryActivity.Companion.KEY_DOWNLOAD_DIR
import com.lanraragi.reader.ui.GalleryActivity.Companion.KEY_FILENAME
import com.lanraragi.reader.ui.GalleryActivity.Companion.KEY_PAGE
import com.lanraragi.reader.ui.GalleryActivity.Companion.KEY_TANK_SEED

/**
 * What a reader launch opens — a directory, a server archive or a whole
 * tankoubon — and the page it asked for, read from the launch intent or the
 * saved instance state.
 */
class ReaderLaunch {

    var action: String? = null
        private set
    var filename: String? = null
        private set
    var downloadDir: String? = null
        private set
    var archive: Archive? = null

    /**
     * Whole-tank composite session seed ([GalleryActivity.ACTION_TANK]).
     * Mutually exclusive with [archive]: a tank session deliberately has NO
     * per-archive identity, which self-disables every per-archive side
     * channel wired through `archive?.let` (continuation panel, per-archive
     * tank progress sync, stamps, history write, reading-session tracking) —
     * their tank-level replacements live in the provider / later commits.
     */
    var tankSeed: TankSessionSeed? = null
        private set

    /** The page the launch asked for; -1 = use the saved progress. */
    var page = 0

    /** Reads the launch intent; returns whether its data arrives as a sticky event instead. */
    fun readIntent(intent: Intent): Boolean {
        action = intent.action
        filename = intent.getStringExtra(KEY_FILENAME)
        downloadDir = intent.getStringExtra(KEY_DOWNLOAD_DIR)
        archive = intent.getParcelableExtra(KEY_ARCHIVE)
        tankSeed = intent.getParcelableExtra(KEY_TANK_SEED)
        val onEvent = intent.getBooleanExtra(DATA_IN_EVENT, false)
        page = intent.getIntExtra(KEY_PAGE, -1)
        return onEvent
    }

    fun restore(savedInstanceState: Bundle) {
        action = savedInstanceState.getString(KEY_ACTION)
        filename = savedInstanceState.getString(KEY_FILENAME)
        downloadDir = savedInstanceState.getString(KEY_DOWNLOAD_DIR)
        archive = savedInstanceState.getParcelable(KEY_ARCHIVE)
        tankSeed = savedInstanceState.getParcelable(KEY_TANK_SEED)
        page = savedInstanceState.getInt(KEY_PAGE, -1)
    }

    fun save(outState: Bundle) {
        outState.putString(KEY_ACTION, action)
        outState.putString(KEY_FILENAME, filename)
        outState.putString(KEY_DOWNLOAD_DIR, downloadDir)
        archive?.let { outState.putParcelable(KEY_ARCHIVE, it) }
        tankSeed?.let { outState.putParcelable(KEY_TANK_SEED, it) }
        outState.putInt(KEY_PAGE, page)
    }

    fun createProvider(context: Context): GalleryProvider2? =
        ReaderSessionSetup.createProvider(context, action, filename, downloadDir, archive, tankSeed)

    companion object {
        /** [original] again, opening on [currentPage] — used to resume the reader after the lock screen. */
        fun resumeIntent(context: Context, original: Intent, currentPage: Int): Intent =
            Intent(context, GalleryActivity::class.java).apply {
                action = original.action
                // Preserve action-specific extras (filename / uri / archive),
                // then override KEY_PAGE with the page the user was actually on.
                original.extras?.let { putExtras(it) }
                original.data?.let { data = it }
                putExtra(KEY_PAGE, currentPage)
                // Don't carry over DATA_IN_EVENT — sticky-event consumption
                // already happened on the original launch.
                removeExtra(DATA_IN_EVENT)
            }
    }
}
