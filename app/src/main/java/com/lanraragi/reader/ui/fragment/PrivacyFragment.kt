package com.lanraragi.reader.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.lanraragi.reader.R
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.settings.SecuritySettings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.lanraragi.reader.client.api.ServerCertificate
import com.lanraragi.reader.client.api.ServerTrust
import com.lanraragi.reader.ui.scene.CertificateTrustDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.DateFormat

class PrivacyFragment : BasePreferenceFragmentCompat() {

    // Any file type: CA files come as .crt, .cer, .pem or .der under
    // inconsistent MIME types; the content decides (audit C27 phase 2).
    private val importCaLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importCa) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.privacy_settings)
        findPreference<Preference>(KEY_CLEAR_SEARCH_HISTORY)?.setOnPreferenceClickListener {
            confirmClearSearchHistory()
            true
        }
        ServerTrust.attach(requireContext())
        findPreference<Preference>(KEY_IMPORT_CA)?.setOnPreferenceClickListener {
            importCaLauncher.launch(arrayOf("*/*"))
            true
        }
        findPreference<Preference>(KEY_TRUSTED_CERTIFICATES)?.setOnPreferenceClickListener {
            showTrustedCertificates()
            true
        }
    }

    private fun confirmClearSearchHistory() {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_privacy_clear_search_history)
            .setMessage(R.string.clear_search_history_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val profileId = LRRAuthManager.getActiveProfileId()
                lifecycleScope.launch {
                    try {
                        ServiceRegistry.dataModule.searchHistoryRepository.clearAll(profileId)
                        Toast.makeText(
                            getContext(), R.string.clear_search_history_done, Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to clear search history", e)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importCa(uri: Uri) {
        val ctx = context ?: return
        val resolver = ctx.contentResolver
        lifecycleScope.launch {
            val added = withContext(ServiceRegistry.coroutineModule.ioDispatcher) {
                try {
                    val bytes = resolver.openInputStream(uri)?.use { readCapped(it) }
                    bytes?.let { ServerTrust.importCertificates(it) }
                } catch (e: Exception) {
                    // Unreadable, too large, or not a certificate: one answer for all.
                    Log.e(TAG, "CA import failed", e)
                    null
                }
            }
            val message = when (added) {
                null -> getString(R.string.import_ca_failed)
                0 -> getString(R.string.import_ca_none_new)
                else -> getString(R.string.import_ca_done, added)
            }
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            updateTrustedCertificatesSummary()
        }
    }

    /** At most [ServerTrust.MAX_IMPORT_BYTES] + 1 bytes, so an oversized file is refused, not read whole. */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (out.size() <= ServerTrust.MAX_IMPORT_BYTES) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun updateTrustedCertificatesSummary() {
        findPreference<Preference>(KEY_TRUSTED_CERTIFICATES)?.summary = getString(
            R.string.settings_privacy_trusted_certificates_summary,
            ServerTrust.importedCas().size,
            ServerTrust.pinnedServers().size,
        )
    }

    /** Imported CAs and trusted servers; tapping one offers to stop trusting it. */
    private fun showTrustedCertificates() {
        val ctx = context ?: return
        val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val cas = ServerTrust.importedCas()
        val pins = ServerTrust.pinnedServers()
        val labels = cas.map {
            getString(
                R.string.trusted_ca_item,
                CertificateTrustDialog.commonName(it.subjectX500Principal.name),
                dates.format(it.notAfter),
            )
        } + pins.map { (address, sha256) -> getString(R.string.trusted_server_item, address, sha256) }
        val builder = AlertDialog.Builder(ctx).setTitle(R.string.settings_privacy_trusted_certificates)
        if (labels.isEmpty()) {
            builder.setMessage(R.string.trusted_certificates_empty)
        } else {
            builder.setItems(labels.toTypedArray()) { _, which ->
                val remove: () -> Unit = if (which < cas.size) {
                    { ServerTrust.removeCa(ServerCertificate.fingerprint(cas[which])) }
                } else {
                    { ServerTrust.forgetPin(pins[which - cas.size].first) }
                }
                confirmRemoveTrust(labels[which], remove)
            }
        }
        builder.setPositiveButton(android.R.string.ok, null).show()
    }

    private fun confirmRemoveTrust(label: String, remove: () -> Unit) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setMessage(getString(R.string.trusted_certificate_remove_confirm, label))
            .setPositiveButton(R.string.trusted_certificate_remove) { _, _ ->
                // Also drops pooled connections: off the main thread.
                lifecycleScope.launch {
                    withContext(ServiceRegistry.coroutineModule.ioDispatcher) { remove() }
                    updateTrustedCertificatesSummary()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateTrustedCertificatesSummary()
        findPreference<Preference>(KEY_PATTERN_PROTECTION)?.setSummary(
            if (!SecuritySettings.hasPattern()) {
                R.string.settings_privacy_pattern_protection_not_set
            } else {
                R.string.settings_privacy_pattern_protection_set
            }
        )
    }

    companion object {
        private const val TAG = "PrivacyFragment"
        private const val KEY_PATTERN_PROTECTION = "pattern_protection"
        private const val KEY_CLEAR_SEARCH_HISTORY = "clear_search_history"
        private const val KEY_IMPORT_CA = "import_ca_certificate"
        private const val KEY_TRUSTED_CERTIFICATES = "trusted_certificates"
        private const val BUFFER_SIZE = 8192
    }
}
