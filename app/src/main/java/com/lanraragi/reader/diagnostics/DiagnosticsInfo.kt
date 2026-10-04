package com.lanraragi.reader.diagnostics

import com.lanraragi.reader.download.DownloadFailureReason
import com.lanraragi.reader.download.DownloadState

/**
 * Facts for `info.txt` in the diagnostics bundle (audit 2026-10-04 C06, R2).
 * Collected by [DiagnosticsCollector]; rendered here so the content and its
 * redaction are unit-testable.
 */
data class DiagnosticsInfo(
    val app: List<Pair<String, String>>,
    val device: List<Pair<String, String>>,
    val databaseVersion: Int?,
    val crashLogEnabled: Boolean,
    val reportCount: Int,
    /** Raw active server URL; only [Redactor.describeServerUrl] of it is written. */
    val serverUrl: String?,
    /** [com.lanraragi.reader.client.api.ServerCapabilityCache.describeServerInfo], or null if not contacted. */
    val serverInfo: String?,
    val downloads: DownloadSummary?,
) {

    data class DownloadSummary(
        val byState: Map<DownloadState, Int>,
        val failures: Map<DownloadFailureReason?, Int>,
        val labels: Int,
    )

    fun render(): String = buildString {
        section("App")
        app.forEach { (k, v) -> line(k, v) }
        line("DatabaseVersion", databaseVersion?.toString() ?: "unknown")
        line("CrashLog", if (crashLogEnabled) "on" else "off")
        line("Reports", reportCount.toString())
        append('\n')

        section("Device")
        device.forEach { (k, v) -> line(k, v) }
        append('\n')

        section("Server")
        line("ActiveServer", Redactor.describeServerUrl(serverUrl))
        line("Info", serverInfo ?: "not contacted in this process")
        append('\n')

        section("Downloads")
        if (downloads == null) {
            append("unavailable\n")
        } else {
            DownloadState.entries.filter { it != DownloadState.INVALID }.forEach { state ->
                line(state.name, (downloads.byState[state] ?: 0).toString())
            }
            line("Labels", downloads.labels.toString())
            downloads.failures.entries
                .sortedByDescending { it.value }
                .forEach { (reason, n) -> line("Failed." + (reason?.name ?: "UNKNOWN"), n.toString()) }
        }
    }

    private fun StringBuilder.section(name: String) {
        append("======== ").append(name).append(" ========\n")
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append('=').append(value).append('\n')
    }
}
