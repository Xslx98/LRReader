package com.lanraragi.reader.client.api

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.lanraragi.reader.ServiceRegistry
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trust for self-hosted HTTPS servers the system does not trust (audit C27
 * phase 2): certificates the user pinned for one server, and CA
 * certificates the user imported.
 *
 * The system's trust always comes first; this only decides what the system
 * rejected:
 * - a **pinned certificate** is accepted for exactly the host:port it was
 *   trusted for (SHA-256 of the leaf, like SSH's known_hosts), whatever its
 *   issuer, dates or names — the user compared its fingerprint;
 * - an **imported CA** vouches for a chain only on a host:port that belongs
 *   to a configured server ([serverClientTrust]) or on the server being
 *   tested in the add / edit / first-run dialogs ([candidateClientTrust]),
 *   and its certificates still have to match the host name.
 *
 * So neither can reach the update check, the update download or the tag
 * dataset (GitHub and the mirror): those hosts are never configured servers
 * and nobody pins them. Trust is stored in plain preferences: none of it is
 * secret, and it is not in backups — a new device asks again.
 */
object ServerTrust {

    private const val TAG = "ServerTrust"
    private const val PREFS = "server_trust"
    private const val PIN_PREFIX = "pin|"
    private const val KEY_CAS = "imported_cas"

    /** Max certificate file size accepted by [importCertificates]. */
    const val MAX_IMPORT_BYTES = 64 * 1024

    @Volatile private var prefs: SharedPreferences? = null

    /** `host|port` → pinned leaf fingerprint. */
    @Volatile private var pins: Map<String, String> = emptyMap()

    @Volatile private var cas: List<X509Certificate> = emptyList()

    @Volatile private var caTrustManager: X509TrustManager? = null

    /** Leaf certificates OkHttp's hostname check rejected, by `host|port` (see [lastHostnameMismatch]). */
    private val hostnameMismatches = ConcurrentHashMap<String, X509Certificate>()

    /** Called whenever trust is withdrawn, so pooled connections stop being reused. */
    @Volatile var onTrustWithdrawn: () -> Unit = ::dropPooledConnections

    /** Load the stored trust. Idempotent; without it nothing beyond the system is trusted. */
    fun attach(context: Context) {
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            pins = p.all.entries
                .filter { it.key.startsWith(PIN_PREFIX) && it.value is String }
                .associate { it.key.removePrefix(PIN_PREFIX) to it.value as String }
            setCas(p.getStringSet(KEY_CAS, emptySet()).orEmpty().mapNotNull(::decodeStored))
            prefs = p
        }
    }

    // ── Pins ─────────────────────────────────────────────────────────────

    fun pinFor(host: String, port: Int): String? = pins[key(host, port)]

    /** Trust [certificate] for its host:port from now on, replacing any earlier pin there. */
    fun trust(certificate: ServerCertificate) {
        synchronized(this) {
            val k = key(certificate.host, certificate.port)
            pins = pins + (k to certificate.sha256)
            prefs?.edit { putString(PIN_PREFIX + k, certificate.sha256) }
        }
        hostnameMismatches.remove(key(certificate.host, certificate.port))
    }

    /** `host:port` display address → pinned fingerprint, sorted by address. */
    fun pinnedServers(): List<Pair<String, String>> =
        pins.entries.map { (k, fp) -> displayAddress(k) to fp }.sortedBy { it.first }

    fun forgetPin(displayAddress: String) {
        val k = pins.keys.firstOrNull { displayAddress(it) == displayAddress } ?: return
        synchronized(this) {
            pins = pins - k
            prefs?.edit { remove(PIN_PREFIX + k) }
        }
        onTrustWithdrawn()
    }

    /** Drop the pin for [host]:[port], e.g. once no server uses that address any more. */
    fun forgetPin(host: String, port: Int) {
        if (pinFor(host, port) == null) return
        forgetPin(displayAddress(key(host, port)))
    }

    // ── Imported CAs ─────────────────────────────────────────────────────

    fun importedCas(): List<X509Certificate> = cas

    /**
     * Add every certificate in [bytes] (PEM, possibly several, or DER) to
     * the imported CAs. Returns how many were new; throws
     * [CertificateException] for a file that holds no certificate.
     */
    fun importCertificates(bytes: ByteArray): Int {
        require(bytes.size <= MAX_IMPORT_BYTES) { "Certificate file too large" }
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
            .filterIsInstance<X509Certificate>()
        if (parsed.isEmpty()) throw CertificateException("No certificate in the file")
        synchronized(this) {
            val known = cas.map(ServerCertificate::fingerprint).toSet()
            val added = parsed.filter { ServerCertificate.fingerprint(it) !in known }
                .distinctBy(ServerCertificate::fingerprint)
            if (added.isEmpty()) return 0
            setCas(cas + added)
            persistCas()
            return added.size
        }
    }

    fun removeCa(sha256: String) {
        synchronized(this) {
            setCas(cas.filterNot { ServerCertificate.fingerprint(it) == sha256 })
            persistCas()
        }
        onTrustWithdrawn()
    }

    private fun setCas(list: List<X509Certificate>) {
        cas = list
        caTrustManager = if (list.isEmpty()) null else trustManagerFor(list)
    }

    private fun persistCas() {
        prefs?.edit {
            putStringSet(KEY_CAS, cas.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }.toSet())
        }
    }

    private fun decodeStored(b64: String): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.decode(b64, Base64.NO_WRAP))) as? X509Certificate
    } catch (e: Exception) {
        Log.e(TAG, "Dropping an unreadable stored CA", e)
        null
    }

    private fun trustManagerFor(anchors: List<X509Certificate>): X509TrustManager? = try {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        anchors.forEachIndexed { i, cert -> store.setCertificateEntry("ca$i", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(store)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    } catch (e: Exception) {
        Log.e(TAG, "Could not build a trust manager from the imported CAs", e)
        null
    }

    private fun trustedByImportedCa(chain: Array<X509Certificate>, authType: String): Boolean {
        val tm = caTrustManager ?: return false
        return try {
            tm.checkServerTrusted(chain, authType)
            true
        } catch (_: CertificateException) {
            false
        }
    }

    // ── Hostname check ───────────────────────────────────────────────────

    /**
     * The certificate OkHttp's hostname check last rejected on [host]:[port]
     * (trusted, but issued for another name — typically a server reached by
     * its IP address), for the trust prompt; null if none.
     */
    fun lastHostnameMismatch(host: String, port: Int): ServerCertificate? =
        hostnameMismatches[key(host, port)]?.let { ServerCertificate.of(it, host, port, pinFor(host, port)) }

    /** The standard hostname check, waived for a certificate pinned on that host:port. */
    val hostnameVerifier: HostnameVerifier = HostnameVerifier { hostname, session ->
        OkHostnameVerifier.verify(hostname, session) || pinnedDespiteName(hostname, session)
    }

    private fun pinnedDespiteName(hostname: String, session: SSLSession): Boolean {
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: javax.net.ssl.SSLPeerUnverifiedException) {
            null
        } ?: return false
        val port = session.peerPort
        if (pinFor(hostname, port) == ServerCertificate.fingerprint(leaf)) return true
        hostnameMismatches[key(hostname, port)] = leaf
        return false
    }

    // ── Trust managers and socket factories ──────────────────────────────

    private val systemTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** For the shared client: imported CAs only vouch for configured servers. */
    val serverClientTrust: Trust by lazy { Trust(ServerTrustManager(::isConfiguredServer)) }

    /** For connection tests: the client only ever talks to the candidate server. */
    val candidateClientTrust: Trust by lazy { Trust(ServerTrustManager { _, _ -> true }) }

    class Trust(val trustManager: X509TrustManager) {
        val sslContext: SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val socketFactory: SSLSocketFactory get() = sslContext.socketFactory
    }

    private fun isConfiguredServer(host: String, port: Int): Boolean {
        val cache = try {
            ServiceRegistry.dataModule.profileLookupCache
        } catch (_: IllegalStateException) {
            // Registry not set up yet: no configured server to vouch for.
            return false
        }
        return cache.findParsedCandidatesByHostPort(host, port).any { it.url.isHttps }
    }

    /**
     * The system's trust manager, then a pin for the peer's host:port, then
     * the imported CAs where [caApplies]. A rejection carries the
     * certificate as an [UntrustedServerCertificateException].
     */
    @SuppressLint("CustomX509TrustManager")
    private class ServerTrustManager(
        private val caApplies: (host: String, port: Int) -> Boolean,
    ) : X509ExtendedTrustManager() {

        private val system get() = systemTrustManager

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
            val session = (socket as? SSLSocket)?.handshakeSession
            decide(chain, authType, session?.peerHost, session?.peerPort ?: -1) {
                (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket)
                    ?: system.checkServerTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
            decide(chain, authType, engine.peerHost, engine.peerPort) {
                (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine)
                    ?: system.checkServerTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            decide(chain, authType, null, -1) { system.checkServerTrusted(chain, authType) }
        }

        private inline fun decide(
            chain: Array<X509Certificate>,
            authType: String,
            host: String?,
            port: Int,
            systemCheck: () -> Unit,
        ) {
            try {
                systemCheck()
                return
            } catch (e: CertificateException) {
                if (host == null || port < 0 || chain.isEmpty()) throw e
                val pinned = pinFor(host, port)
                if (pinned != null && pinned == ServerCertificate.fingerprint(chain[0])) return
                if (caApplies(host, port) && trustedByImportedCa(chain, authType)) return
                throw UntrustedServerCertificateException(ServerCertificate.of(chain[0], host, port, pinned), e)
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    /**
     * Withdrawn trust must not live on in kept-alive connections or resumable
     * TLS sessions (a resumed session skips the certificate check).
     */
    private fun dropPooledConnections() {
        try {
            ServiceRegistry.networkModule.okHttpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            Log.e(TAG, "Could not evict pooled connections", e)
        }
        for (trust in listOf(serverClientTrust, candidateClientTrust)) purgeSessions(trust.sslContext)
    }

    /**
     * Drop the cached sessions of [context]. Conscrypt's session handles
     * refuse invalidate() (UnsupportedOperationException), but lowering
     * the context's timeout removes every session older than it; the old
     * timeout is then restored.
     */
    private fun purgeSessions(context: SSLContext) {
        try {
            val sessions = context.clientSessionContext
            val timeout = sessions.sessionTimeout
            sessions.sessionTimeout = 1
            sessions.sessionTimeout = timeout
        } catch (e: Exception) {
            Log.e(TAG, "Could not purge cached TLS sessions", e)
        }
    }

    private fun key(host: String, port: Int) = "$host|$port"

    private fun displayAddress(key: String): String {
        val host = key.substringBeforeLast('|')
        val port = key.substringAfterLast('|')
        return if (':' in host) "[$host]:$port" else "$host:$port"
    }

    /** Tests only: forget the attached store and every trust decision. */
    internal fun resetForTesting() {
        synchronized(this) {
            prefs = null
            pins = emptyMap()
            setCas(emptyList())
        }
        hostnameMismatches.clear()
        onTrustWithdrawn = ::dropPooledConnections
    }
}
