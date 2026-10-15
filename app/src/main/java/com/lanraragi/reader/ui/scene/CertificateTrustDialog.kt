package com.lanraragi.reader.ui.scene

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lanraragi.reader.R
import com.lanraragi.reader.client.api.ServerCertificate
import com.lanraragi.reader.client.api.ServerTrust
import java.text.DateFormat
import java.util.Date

/**
 * Asks whether to trust a server certificate the system does not (audit
 * C27 phase 2): its address, names, validity and SHA-256 fingerprint, with
 * a warning when it replaces a certificate trusted before. Trusting pins it
 * for that host:port ([ServerTrust.trust]) and runs [onTrusted], which
 * repeats the connection test that met it.
 */
internal object CertificateTrustDialog {

    fun show(context: Context, certificate: ServerCertificate, onTrusted: () -> Unit) {
        val changed = certificate.previousSha256 != null
        val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val issuer = commonName(certificate.issuer).let {
            if (certificate.selfSigned) context.getString(R.string.cert_self_signed, it) else it
        }
        val message = buildString {
            if (changed) append(context.getString(R.string.cert_changed_warning)).append("\n\n")
            append(
                context.getString(
                    R.string.cert_details,
                    certificate.displayAddress,
                    commonName(certificate.subject),
                    issuer,
                    dates.format(Date(certificate.notBeforeMillis)),
                    dates.format(Date(certificate.notAfterMillis)),
                    certificate.sha256,
                )
            )
            certificate.previousSha256?.let {
                append("\n\n").append(context.getString(R.string.cert_previous_fingerprint, it))
            }
            append("\n\n").append(context.getString(R.string.cert_compare_hint))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (changed) R.string.cert_changed_title else R.string.cert_untrusted_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cert_trust_action) { _, _ ->
                ServerTrust.trust(certificate)
                onTrusted()
            }
            .show()
        // The fingerprint is there to be compared: let it be copied.
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    /** The CN of an X.500 name, or the whole name when it has none. */
    fun commonName(x500: String): String =
        Regex("(?:^|,)\\s*CN=([^,]+)").find(x500)?.groupValues?.get(1)?.trim() ?: x500
}
