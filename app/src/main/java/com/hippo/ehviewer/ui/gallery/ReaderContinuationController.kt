package com.hippo.ehviewer.ui.gallery

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.hippo.ehviewer.R
import com.hippo.ehviewer.gallery.NextArchiveResolver
import com.hippo.ehviewer.gallery.ReadingContextStore
import com.hippo.ehviewer.settings.ReadingSettings
import com.lanraragi.reader.domain.Archive
import kotlinx.coroutines.launch

/**
 * Owns the end-of-book continuation flow for GalleryActivity (the activity
 * stays a coordinator per project convention). All entry points must be
 * called on the MAIN thread.
 */
class ReaderContinuationController(
    private val root: View,
    private val scope: LifecycleCoroutineScope,
    private val resolver: NextArchiveResolver,
    private val launchNext: (Archive) -> Unit,
) {

    private val stateText: TextView = root.findViewById(R.id.continuation_state)
    private val nextTitle: TextView = root.findViewById(R.id.continuation_next_title)
    private val primary: Button = root.findViewById(R.id.continuation_primary)
    private val secondary: Button = root.findViewById(R.id.continuation_secondary)

    private var currentArcid: String? = null
    private var cached: NextArchiveResolver.NextResult? = null
    private var resolving = false
    private var lastEventAt = 0L

    init {
        root.setOnClickListener { hide() }
        secondary.setOnClickListener { hide() }
        primary.setOnClickListener { onPrimary() }
    }

    /** Set (or switch) the archive this reader session shows. */
    fun setCurrentArchive(arcid: String) {
        if (currentArcid != arcid) {
            currentArcid = arcid
            cached = null
        }
    }

    val isShowing: Boolean get() = root.visibility == View.VISIBLE

    fun hide() {
        root.visibility = View.GONE
    }

    /**
     * Forward attempt at the last page (or auto-read completion). The same GL
     * event also fires on TOP over-scroll in vertical mode, so [atLastPage]
     * is the caller-side guard.
     */
    fun onEndOfBookEvent(atLastPage: Boolean) {
        // No archive (e.g. an ACTION_VIEW import session): resolveAsync could
        // never run, so bail before any UI or the panel would sit on a
        // perpetual "loading" state.
        if (currentArcid == null) return
        if (!atLastPage || !ReadingSettings.getReaderContinuation()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventAt < DEBOUNCE_MS) return
        lastEventAt = now

        val known = cached
        if (ReadingSettings.getReaderContinuationSwipe() && known is NextArchiveResolver.NextResult.Next) {
            jumpTo(known)
            return
        }
        show(known)
    }

    private fun onPrimary() {
        when (val r = cached) {
            is NextArchiveResolver.NextResult.Next -> jumpTo(r)
            is NextArchiveResolver.NextResult.Error -> {
                cached = null
                show(null)
            }
            else -> hide()
        }
    }

    private fun jumpTo(next: NextArchiveResolver.NextResult.Next) {
        Toast.makeText(
            root.context,
            root.context.getString(R.string.continuation_jump_toast, next.archive.title),
            Toast.LENGTH_SHORT,
        ).show()
        ReadingContextStore.advance(next.advanced)
        launchNext(next.archive)
    }

    private fun show(known: NextArchiveResolver.NextResult?) {
        root.visibility = View.VISIBLE
        if (known == null) {
            render(loading = true)
            resolveAsync()
        } else {
            render(loading = false)
        }
    }

    private fun resolveAsync() {
        val arcid = currentArcid ?: return
        if (resolving) return
        resolving = true
        scope.launch {
            cached = resolver.resolve(arcid)
            resolving = false
            if (isShowing) render(loading = false)
        }
    }

    private fun render(loading: Boolean) {
        val r = cached
        nextTitle.visibility = View.GONE
        primary.visibility = View.GONE
        when {
            loading -> stateText.setText(R.string.continuation_loading)
            r is NextArchiveResolver.NextResult.Next -> {
                stateText.setText(R.string.continuation_next_label)
                nextTitle.text = r.archive.title
                nextTitle.visibility = View.VISIBLE
                primary.setText(R.string.continuation_read_next)
                primary.visibility = View.VISIBLE
            }
            r is NextArchiveResolver.NextResult.EndOfList ->
                stateText.setText(R.string.continuation_end_of_list)
            r is NextArchiveResolver.NextResult.Error -> {
                stateText.setText(R.string.continuation_error)
                primary.setText(R.string.continuation_retry)
                primary.visibility = View.VISIBLE
            }
            else -> hide() // NoContext: keep legacy behaviour, no panel
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
    }
}
