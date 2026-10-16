package com.lanraragi.reader.client.api

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lanraragi.reader.ServiceRegistry
import com.lanraragi.reader.containedTestScope
import com.lanraragi.reader.dao.AppDatabase
import com.lanraragi.reader.dao.ProfileRepository
import com.lanraragi.reader.dao.ServerProfile
import com.lanraragi.reader.module.IDataModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit

/**
 * Audit C27 phase 2: real TLS handshakes against a MockWebServer with
 * self-signed and CA-issued certificates, through [ServerTrust]'s trust
 * managers and hostname check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ServerTrustTest {

    private lateinit var ctx: Context
    private lateinit var server: MockWebServer
    private var withdrawn = 0
    private var db: AppDatabase? = null
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("server_trust", Context.MODE_PRIVATE).edit().clear().commit()
        ServerTrust.resetForTesting()
        ServerTrust.attach(ctx)
        ServerTrust.onTrustWithdrawn = { withdrawn++ }
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ServerTrust.resetForTesting()
        scope?.cancel()
        db?.close()
        ServiceRegistry.initializeForTest(data = UnsetDataModule)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun serve(leaf: HeldCertificate, vararg intermediates: HeldCertificate) {
        val certs = HandshakeCertificates.Builder()
            .heldCertificate(leaf, *intermediates.map { it.certificate }.toTypedArray())
            .build()
        server.useHttps(certs.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(SERVER_INFO_JSON))
        server.start()
    }

    private fun selfSigned(vararg names: String = LOOPBACK_NAMES) = HeldCertificate.Builder()
        .commonName("lrr.test")
        .apply { names.forEach { addSubjectAlternativeName(it) } }
        .build()

    private fun client(trust: ServerTrust.Trust) = OkHttpClient.Builder()
        .sslSocketFactory(trust.socketFactory, trust.trustManager)
        .hostnameVerifier(ServerTrust.hostnameVerifier)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun get(trust: ServerTrust.Trust = ServerTrust.candidateClientTrust): Int =
        client(trust).newCall(Request.Builder().url(server.url("/api/info")).build()).execute().use { it.code }

    /** The request must fail; returns the failure. */
    private fun failure(trust: ServerTrust.Trust = ServerTrust.candidateClientTrust): IOException {
        try {
            get(trust)
        } catch (e: IOException) {
            return e
        }
        fail("the handshake should have been refused")
        throw AssertionError()
    }

    private fun untrusted(e: IOException) = untrustedCertificateIn(e, server.hostName, server.port)

    // ── pins ─────────────────────────────────────────────────────────────

    @Test
    fun selfSigned_isRefusedWithItsCertificate() {
        val cert = selfSigned()
        serve(cert)

        val shown = untrusted(failure())

        assertNotNull(shown)
        assertEquals(ServerCertificate.fingerprint(cert.certificate), shown!!.sha256)
        assertEquals(server.port, shown.port)
        assertTrue(shown.selfSigned)
        assertNull("nothing was trusted before", shown.previousSha256)
    }

    @Test
    fun trustedCertificate_connects() {
        serve(selfSigned())
        ServerTrust.trust(untrusted(failure())!!)
        server.enqueue(MockResponse().setBody(SERVER_INFO_JSON))

        assertEquals(200, get())
        assertEquals("the shared client honours the pin too", 200, get(ServerTrust.serverClientTrust))
    }

    @Test
    fun pin_appliesToItsPortOnly() {
        val cert = selfSigned()
        serve(cert)
        val shown = untrusted(failure())!!
        ServerTrust.trust(shown.copy(port = shown.port + 1))

        assertNotNull(untrusted(failure()))
    }

    @Test
    fun pinnedCertificate_waivesTheHostnameCheck() {
        // Issued for another name, as a server reached by its IP address is.
        serve(selfSigned("some.other.name"))
        ServerTrust.trust(untrusted(failure())!!)

        assertEquals(200, get())
    }

    @Test
    fun changedCertificate_reportsThePreviousFingerprint() {
        serve(selfSigned())
        val shown = untrusted(failure())!!
        ServerTrust.trust(shown.copy(sha256 = "AA:BB"))

        assertEquals("AA:BB", untrusted(failure())!!.previousSha256)
    }

    @Test
    fun forgottenPin_isRefusedAgain_andDropsPooledConnections() {
        serve(selfSigned())
        val shown = untrusted(failure())!!
        ServerTrust.trust(shown)
        assertEquals(1, ServerTrust.pinnedServers().size)

        ServerTrust.forgetPin(shown.host, shown.port)

        assertTrue(ServerTrust.pinnedServers().isEmpty())
        assertEquals(1, withdrawn)
    }

    @Test
    fun forgottenPin_withTheRealCleanup_refusesTheNextConnection() {
        serve(selfSigned())
        val shown = untrusted(failure())!!
        ServerTrust.trust(shown)
        server.enqueue(MockResponse().setBody(SERVER_INFO_JSON))
        assertEquals(200, get())
        // The production withdrawal: pooled connections and cached TLS
        // sessions go too (a resumed session would skip the trust check).
        ServerTrust.resetForTesting()
        ServerTrust.attach(ctx)
        Thread.sleep(1_100) // older than the 1 s purge window

        ServerTrust.forgetPin(shown.host, shown.port)

        assertNotNull("a new connection must not resume the trusted session", untrusted(failure()))
    }

    @Test
    fun trust_survivesARestart() {
        serve(selfSigned())
        ServerTrust.trust(untrusted(failure())!!)

        ServerTrust.resetForTesting()
        ServerTrust.attach(ctx)

        assertEquals(200, get())
    }

    // ── imported CAs ─────────────────────────────────────────────────────

    private fun caAndLeaf(vararg names: String = LOOPBACK_NAMES): Pair<HeldCertificate, HeldCertificate> {
        val ca = HeldCertificate.Builder().commonName("Home CA").certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().commonName("lrr.home")
            .apply { names.forEach { addSubjectAlternativeName(it) } }
            .signedBy(ca).build()
        return ca to leaf
    }

    @Test
    fun importedCa_vouchesForTheServerUnderTest() {
        val (ca, leaf) = caAndLeaf()
        serve(leaf, ca)
        assertNotNull(untrusted(failure()))

        assertEquals(1, ServerTrust.importCertificates(ca.certificatePem().toByteArray()))

        assertEquals(200, get(ServerTrust.candidateClientTrust))
    }

    @Test
    fun importedCa_doesNotVouchForAnUnconfiguredHost_onTheSharedClient() {
        val (ca, leaf) = caAndLeaf()
        serve(leaf, ca)
        ServerTrust.importCertificates(ca.certificatePem().toByteArray())
        installProfiles(emptyList())

        assertNotNull("e.g. the update host: system trust only", untrusted(failure(ServerTrust.serverClientTrust)))
    }

    @Test
    fun importedCa_vouchesForAConfiguredHttpsServer_onTheSharedClient() {
        val (ca, leaf) = caAndLeaf()
        serve(leaf, ca)
        ServerTrust.importCertificates(ca.certificatePem().toByteArray())
        installProfiles(listOf("https://${server.hostName}:${server.port}"))

        assertEquals(200, get(ServerTrust.serverClientTrust))
    }

    @Test
    fun importedCa_stillChecksTheHostName() {
        val (ca, leaf) = caAndLeaf("some.other.name")
        serve(leaf, ca)
        ServerTrust.importCertificates(ca.certificatePem().toByteArray())

        val e = failure()

        assertTrue(e is javax.net.ssl.SSLPeerUnverifiedException)
        // The trust prompt can still offer to pin it for this address.
        assertEquals(ServerCertificate.fingerprint(leaf.certificate), untrusted(e)?.sha256)
    }

    @Test
    fun importCertificates_countsOnlyNewOnes_andRejectsGarbage() {
        val (ca, _) = caAndLeaf()
        val pem = ca.certificatePem().toByteArray()
        assertEquals(1, ServerTrust.importCertificates(pem))
        assertEquals(0, ServerTrust.importCertificates(pem))

        try {
            ServerTrust.importCertificates("not a certificate".toByteArray())
            fail("garbage must not import")
        } catch (e: CertificateException) {
            // expected
        }
        assertEquals(1, ServerTrust.importedCas().size)
    }

    @Test
    fun removedCa_isForgotten_acrossRestarts() {
        val (ca, _) = caAndLeaf()
        ServerTrust.importCertificates(ca.certificatePem().toByteArray())
        ServerTrust.resetForTesting()
        ServerTrust.attach(ctx)
        ServerTrust.onTrustWithdrawn = { withdrawn++ }
        assertEquals(1, ServerTrust.importedCas().size)

        ServerTrust.removeCa(ServerCertificate.fingerprint(ca.certificate))
        ServerTrust.resetForTesting()
        ServerTrust.attach(ctx)

        assertTrue(ServerTrust.importedCas().isEmpty())
        assertEquals(1, withdrawn)
    }

    // ── connection test ──────────────────────────────────────────────────

    @Test
    fun connectWithFallback_offersTheCertificate_thenConnectsOnceTrusted() = runBlocking {
        serve(selfSigned())
        server.enqueue(MockResponse().setBody(SERVER_INFO_JSON))
        val test = LRRUrlHelper.buildTestClient(OkHttpClient())
        val url = "https://${server.hostName}:${server.port}"

        val first = LRRUrlHelper.connectWithFallback(test, url, null)
        assertTrue(first is LRRUrlHelper.ConnectResult.UntrustedCertificate)
        ServerTrust.trust((first as LRRUrlHelper.ConnectResult.UntrustedCertificate).certificate)

        val second = LRRUrlHelper.connectWithFallback(test, url, null)
        assertTrue(second is LRRUrlHelper.ConnectResult.Success)
    }

    @Test
    fun certificateFailures_haveTheirOwnMessage() {
        serve(selfSigned())
        assertTrue(isCertificateTrustFailure(failure()))
        assertFalse(isCertificateTrustFailure(IOException("reset")))
    }

    // ── registry ─────────────────────────────────────────────────────────

    private fun installProfiles(urls: List<String>) {
        val database = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        db = database
        val repo = ProfileRepository(database.miscDao())
        runBlocking { urls.forEach { repo.insert(ServerProfile(name = "S", url = it)) } }
        val testScope = containedTestScope()
        scope = testScope
        val cache = ProfileLookupCache(repo, testScope)
        runBlocking { cache.awaitInitialized() }
        ServiceRegistry.initializeForTest(data = object : IDataModule by UnsetDataModule {
            override val profileLookupCache get() = cache
        })
    }

    private object UnsetDataModule : IDataModule {
        override val searchHistoryRepository get() = unset()
        override val downloadDbRepository get() = unset()
        override val downloadManager get() = unset()
        override val favouriteStatusRouter get() = unset()
        override val historyRepository get() = unset()
        override val profileRepository get() = unset()
        override val profileLookupCache get() = unset()
        override val quickSearchRepository get() = unset()
        override val favoritesRepository get() = unset()
        override val archiveDetailCache get() = unset()
        override val spiderInfoCache get() = unset()
        override fun clearArchiveDetailCache() {}
    }

    private companion object {
        fun unset(): Nothing = throw IllegalStateException("not set up in this test")

        /**
         * MockWebServer listens on loopback; its URLs use the address's canonical
         * name, which is "127.0.0.1" on Windows but "localhost" on the Linux CI
         * runner. Certificates that must pass the hostname check carry both.
         */
        val LOOPBACK_NAMES = arrayOf("127.0.0.1", "localhost")

        const val SERVER_INFO_JSON = """{"name":"Test Server","version":"0.9.0","archives_per_page":100}"""
    }
}
