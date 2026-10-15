package com.lanraragi.reader.client.api

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * A server certificate the system does not trust, as shown to the user
 * before they decide to trust it for [host]:[port] (audit C27 phase 2).
 *
 * [sha256] is the SHA-256 fingerprint of the leaf certificate in the form
 * browsers and `openssl x509 -fingerprint -sha256` print, so the user can
 * compare it with what the server shows. [previousSha256] is set when a
 * different certificate was trusted for this server before: the
 * certificate changed, either renewed or intercepted.
 */
data class ServerCertificate(
    val host: String,
    val port: Int,
    val subject: String,
    val issuer: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    val sha256: String,
    val selfSigned: Boolean,
    val previousSha256: String?,
) {
    /** `host:port`, with an IPv6 literal in brackets. */
    val displayAddress: String
        get() = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        fun of(leaf: X509Certificate, host: String, port: Int, previousSha256: String?) = ServerCertificate(
            host = host,
            port = port,
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notBeforeMillis = leaf.notBefore.time,
            notAfterMillis = leaf.notAfter.time,
            sha256 = fingerprint(leaf),
            selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
            previousSha256 = previousSha256,
        )

        /** SHA-256 over the certificate's DER encoding: upper-case hex pairs joined by ':'. */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }
}

/**
 * The server's certificate is neither trusted by the system, nor pinned for
 * this host:port, nor issued by an imported CA. Carried as the cause of the
 * handshake failure so a connection test can offer to trust it.
 */
class UntrustedServerCertificateException(
    val certificate: ServerCertificate,
    cause: Throwable?,
) : CertificateException("Untrusted server certificate for ${certificate.displayAddress}", cause)

/**
 * The certificate in [error]'s cause chain that a connection to [host]:[port]
 * could not trust, or null if [error] is not a certificate-trust failure.
 * A hostname mismatch fails after the handshake, in OkHttp's hostname check,
 * with no certificate attached; [ServerTrust] remembers that certificate.
 */
fun untrustedCertificateIn(error: Throwable, host: String, port: Int): ServerCertificate? {
    var t: Throwable? = error
    var depth = 0
    while (t != null && depth < MAX_CAUSE_DEPTH) {
        if (t is UntrustedServerCertificateException) return t.certificate
        if (t is javax.net.ssl.SSLPeerUnverifiedException) return ServerTrust.lastHostnameMismatch(host, port)
        t = t.cause
        depth++
    }
    return null
}

/** Whether [error] is a server certificate the app does not trust, or one issued for another name. */
fun isCertificateTrustFailure(error: Throwable): Boolean {
    var t: Throwable? = error
    var depth = 0
    while (t != null && depth < MAX_CAUSE_DEPTH) {
        if (t is UntrustedServerCertificateException || t is javax.net.ssl.SSLPeerUnverifiedException) return true
        t = t.cause
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
