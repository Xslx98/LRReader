package com.hippo.ehviewer.widget

import android.app.DownloadManager
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ArchiverDownloadProgress @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes) {

    private val myTextView: TextView
    private val myProgressBar: ProgressBar

    @Volatile
    private var showing = false
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private var reasonString = "Unknown"

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_archiver_progress, this)
        myTextView = findViewById(R.id.archiver_downloading)
        myProgressBar = findViewById(R.id.archiver_progress)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pollJob?.cancel()
        pollJob = null
    }

    fun initThread(galleryInfo: GalleryInfo?) {
        if (galleryInfo == null) return
        if (showing) return
        val dId = Settings.getArchiverDownloadId(galleryInfo.gid)
        if (dId == -1L) return

        showing = true
        visibility = VISIBLE
        myTextView.text = context.getString(R.string.archiver_downloading, "0%")
        myProgressBar.progress = 0

        pollJob = scope.launch {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().apply { setFilterById(dId) }
            try {
                var done = false
                while (!done && isActive) {
                    val pollResult = withContext(Dispatchers.IO) {
                        val cursor = downloadManager.query(query)
                        if (!cursor.moveToNext()) {
                            cursor.close()
                            return@withContext null // download entry gone
                        }
                        val state = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        )
                        if (state == DownloadManager.STATUS_PAUSED) {
                            val reason = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            )
                            cursor.close()
                            return@withContext PollResult.Paused(reason)
                        }
                        val downloaded = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        ).toDouble()
                        val total = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        ).toDouble()
                        cursor.close()
                        val progress = downloaded / total * 100.0
                        PollResult.Progress(progress)
                    }

                    if (pollResult == null) break // download entry gone

                    when (pollResult) {
                        is PollResult.Paused -> {
                            reasonString = when (pollResult.reason) {
                                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for WiFi"
                                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for connectivity"
                                DownloadManager.PAUSED_WAITING_TO_RETRY -> "Waiting to retry"
                                else -> reasonString
                            }
                            Toast.makeText(context, reasonString, Toast.LENGTH_LONG).show()
                            delay(6000)
                        }
                        is PollResult.Progress -> {
                            val result = String.format(Locale.getDefault(), "%.2f", pollResult.value) + "%"
                            myTextView.text = context.getString(R.string.archiver_downloading, result)
                            myProgressBar.progress = pollResult.value.toInt()
                            delay(1000)
                            if (pollResult.value >= 100) done = true
                        }
                    }
                }
                if (isActive) {
                    delay(6000)
                    this@ArchiverDownloadProgress.visibility = GONE
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                if (isActive) {
                    myTextView.setText(R.string.download_state_failed)
                }
            } finally {
                showing = false
            }
        }
    }

    private sealed interface PollResult {
        data class Paused(val reason: Int) : PollResult
        data class Progress(val value: Double) : PollResult
    }
}
