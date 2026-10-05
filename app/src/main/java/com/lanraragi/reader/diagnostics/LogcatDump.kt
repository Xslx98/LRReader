package com.lanraragi.reader.diagnostics

import android.os.Process
import android.util.Log
import java.io.IOException
import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * Dumps this process's recent logcat lines (audit 2026-10-04 STAB-09).
 * Replaces the old `LogCat.save`, which ran `logcat -d` for the whole device
 * on the main thread and never closed the stream or the process. Blocking:
 * call it off the main thread.
 */
object LogcatDump {

    private const val TAG = "LogcatDump"
    private const val MAX_LINES = 5000
    private const val TIMEOUT_SECONDS = 10L

    internal fun command(pid: Int, maxLines: Int = MAX_LINES): List<String> =
        listOf("logcat", "-d", "-v", "threadtime", "-t", maxLines.toString(), "--pid=$pid")

    /**
     * Writes the redacted dump into [out].
     * @return false if logcat could not be run.
     */
    fun writeTo(out: Writer, command: List<String> = command(Process.myPid())): Boolean {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            Log.e(TAG, "Start logcat", e)
            return false
        }
        try {
            process.outputStream.close()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { out.write(Redactor.redact(it)); out.write("\n") }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.e(TAG, "logcat did not exit in ${TIMEOUT_SECONDS}s")
            }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Read logcat", e)
            return false
        } finally {
            process.destroy()
        }
    }
}
