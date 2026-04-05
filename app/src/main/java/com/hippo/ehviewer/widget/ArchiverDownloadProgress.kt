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
import com.hippo.util.IoThreadPoolExecutor
import java.util.Locale
import java.util.concurrent.Future

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

    @Volatile
    private var cancelled = false
    private var pollTask: Future<*>? = null

    private var reasonString = "Unknown"

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_archiver_progress, this)
        myTextView = findViewById(R.id.archiver_downloading)
        myProgressBar = findViewById(R.id.archiver_progress)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelled = true
        pollTask?.cancel(true)
        pollTask = null
    }

    fun initThread(galleryInfo: GalleryInfo?) {
        if (galleryInfo == null) return
        if (showing) return
        val dId = Settings.getArchiverDownloadId(galleryInfo.gid)
        if (dId == -1L) return

        showing = true
        cancelled = false
        visibility = VISIBLE
        myTextView.text = context.getString(R.string.archiver_downloading, "0%")
        myProgressBar.progress = 0

        pollTask = IoThreadPoolExecutor.instance.submit {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().apply { setFilterById(dId) }
            try {
                var done = false
                while (!done && !cancelled) {
                    val cursor = downloadManager.query(query)
                    val queryResult = cursor.moveToNext()
                    if (!queryResult) {
                        cursor.close()
                        break
                    }
                    val state = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    if (state == DownloadManager.STATUS_PAUSED) {
                        val reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                        )
                        when (reason) {
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
                                reasonString = "Waiting for WiFi"
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK ->
                                reasonString = "Waiting for connectivity"
                            DownloadManager.PAUSED_WAITING_TO_RETRY ->
                                reasonString = "Waiting to retry"
                        }
                        cursor.close()
                        post { Toast.makeText(context, reasonString, Toast.LENGTH_LONG).show() }
                        Thread.sleep(6000)
                        continue
                    }
                    val downloaded = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    ).toDouble()
                    val total = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    ).toDouble()
                    cursor.close()
                    val progress = downloaded / total * 100.0
                    val result = String.format(Locale.getDefault(), "%.2f", progress) + "%"
                    if (!cancelled) {
                        myTextView.post {
                            val text = context.getString(R.string.archiver_downloading, result)
                            myTextView.text = text
                        }
                        myProgressBar.post { myProgressBar.progress = progress.toInt() }
                    }
                    Thread.sleep(1000)
                    if (progress < 100) continue
                    done = true
                }
                if (!cancelled) {
                    Thread.sleep(6000)
                    this.post { this.visibility = GONE }
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                if (!cancelled) {
                    myTextView.post { myTextView.setText(R.string.download_state_failed) }
                }
            } catch (e: InterruptedException) {
                if (!cancelled) {
                    myTextView.post { myTextView.setText(R.string.download_state_failed) }
                }
            } finally {
                showing = false
            }
        }
    }
}
