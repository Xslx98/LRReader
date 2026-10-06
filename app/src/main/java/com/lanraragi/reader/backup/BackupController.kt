package com.lanraragi.reader.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.lanraragi.reader.LRReaderApplication
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.Settings
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.gallery.GalleryProvider2
import com.lanraragi.reader.settings.DownloadSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Advanced → back up / restore (audit C05, ruling R18). Files go
 * through the system document picker; nothing is written without the user
 * choosing a place. The restore itself runs on the app-wide IO scope so
 * leaving the screen cannot cut it short; the app restarts afterwards because
 * the download manager and caches hold in-memory copies of what changed.
 *
 * Construct during the fragment's onCreate (it registers activity-result launchers).
 */
class BackupController(private val fragment: Fragment) {

    private val createDocument = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_JSON)
    ) { uri -> if (uri != null) export(uri) }

    private val openDocument = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readForRestore(uri) }

    fun startBackup() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        createDocument.launch("LRReader-backup-$stamp.json")
    }

    fun startRestore() {
        openDocument.launch(arrayOf(MIME_JSON, "application/octet-stream", "text/plain"))
    }

    private fun export(uri: Uri) {
        val app = fragment.requireContext().applicationContext
        ServiceRegistry.coroutineModule.ioScope.launch {
            val ok = try {
                val backup = exporter(app).collect()
                val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
                out.use { BackupCodec.write(backup, it) }
                true
            } catch (e: IOException) {
                Log.e(TAG, "Backup export failed", e)
                false
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (ok) R.string.backup_done else R.string.backup_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readForRestore(uri: Uri) {
        val app = fragment.requireContext().applicationContext
        ServiceRegistry.coroutineModule.ioScope.launch {
            val backup = try {
                app.contentResolver.openInputStream(uri)?.use { BackupCodec.read(it) }
            } catch (e: IOException) {
                Log.e(TAG, "Backup file unreadable", e)
                null
            }
            withContext(Dispatchers.Main) {
                if (backup == null) {
                    Toast.makeText(app, R.string.restore_invalid, Toast.LENGTH_LONG).show()
                } else {
                    confirmRestore(backup)
                }
            }
        }
    }

    private fun confirmRestore(backup: LrrBackup) {
        val context = fragment.context ?: return
        val created = DateFormat.getDateTimeInstance().format(Date(backup.createdAt * MILLIS))
        AlertDialog.Builder(context)
            .setTitle(R.string.restore_confirm_title)
            .setMessage(
                context.getString(R.string.restore_confirm_message, created, backup.profiles.size, backup.archives.size)
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_action) { _, _ -> restore(context, backup) }
            .show()
    }

    private fun restore(context: Context, backup: LrrBackup) {
        val app = context.applicationContext
        val progress = AlertDialog.Builder(context)
            .setMessage(R.string.restore_running)
            .setCancelable(false)
            .show()
        ServiceRegistry.coroutineModule.ioScope.launch {
            val result = try {
                importer(app).restore(backup)
            } catch (e: Exception) {
                // The database part is one transaction: a failure leaves it unchanged.
                Log.e(TAG, "Restore failed", e)
                null
            }
            withContext(Dispatchers.Main) {
                progress.dismiss()
                showResult(result)
            }
        }
    }

    private fun showResult(result: BackupImporter.Result?) {
        val context = fragment.context
        val app = fragment.activity?.application as? LRReaderApplication
        if (context == null || app == null) {
            app?.restart()
            return
        }
        if (result == null) {
            Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_LONG).show()
            return
        }
        val counts = context.getString(
            R.string.restore_done_message,
            result.archivesRestored, result.downloadsRelinked, result.downloadsSkipped, result.profilesAdded,
        )
        val keys = if (result.profilesAdded > 0) context.getString(R.string.restore_done_keys) else null
        val message = listOfNotNull(counts, keys, context.getString(R.string.restore_done_restart))
            .joinToString("\n\n")
        AlertDialog.Builder(context)
            .setTitle(R.string.restore_done_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> app.restart() }
            .show()
    }

    private fun exporter(app: Context) = BackupExporter(
        AppDatabase.getInstance(app), Settings.getPreferences(), readingProgress(app),
    )

    private fun importer(app: Context) = BackupImporter(
        AppDatabase.getInstance(app), Settings.getPreferences(), readingProgress(app),
        DownloadRelinker.onDisk(app), DownloadSettings::getCurrentDownloadRootUri,
    )

    private fun readingProgress(app: Context) =
        app.getSharedPreferences(GalleryProvider2.SP_READING_PROGRESS, Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "BackupController"
        const val MIME_JSON = "application/json"
        const val MILLIS = 1000L
    }
}
