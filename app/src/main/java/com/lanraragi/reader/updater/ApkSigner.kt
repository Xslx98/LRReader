package com.lanraragi.reader.updater

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import java.io.File
import java.security.MessageDigest

/**
 * Signer checks around the self-update (audit 2026-10-04 SEC-12, ruling R21).
 * The installer refuses an APK signed by another key anyway, but with a
 * confusing message; checking first lets the app say why and drop the file.
 */
internal object ApkSigner {

    /** SHA-256 hex of every certificate in [info] (current signers and rotation history). */
    fun digests(info: SigningInfo?): Set<String> {
        if (info == null) return emptySet()
        val certs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return certs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    /** True when the archive shares a signing certificate with the installed app. */
    fun sameSigner(installed: Set<String>, archive: Set<String>): Boolean =
        installed.isNotEmpty() && installed.any { it in archive }

    /** Whether [apk] is signed like the running app; false when either side is unreadable. */
    fun matchesInstalled(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(context.packageName, flags).signingInfo
        val archive = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo
        return sameSigner(digests(installed), digests(archive))
    }

    /**
     * Update APKs in `cache/updates` that are older than the running install
     * (the update they carried is installed now); the rest are kept for a
     * pending install.
     */
    fun staleUpdates(dir: File, lastUpdateTime: Long): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && it.lastModified() <= lastUpdateTime }

    /** Deletes [staleUpdates] for the running install. Called once per process start. */
    fun purgeInstalledUpdates(context: Context) {
        val dir = File(context.cacheDir, ApkDownloader.UPDATES_DIR)
        if (!dir.isDirectory) return
        val lastUpdateTime = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        staleUpdates(dir, lastUpdateTime).forEach { it.delete() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
