package com.lanraragi.reader.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-10-04 C06 (R2): nothing that identifies the server or a key survives. */
class RedactorTest {

    @Test
    fun urlAuthority_isReplaced_schemeAndPathKept() {
        val out = Redactor.redact("GET https://lrr.example.com:3000/api/archives/abc/files failed")
        assertEquals("GET https://<host>/api/archives/abc/files failed", out)
    }

    @Test
    fun userInfoInUrl_isRemovedWithTheHost() {
        val out = Redactor.redact("http://admin:hunter2@10.0.0.5:3000/api/info")
        assertFalse(out, out.contains("hunter2"))
        assertFalse(out, out.contains("10.0.0.5"))
        assertEquals("http://<host>/api/info", out)
    }

    @Test
    fun bareIpv4_isReplaced() {
        assertEquals("connect to <ip> timed out", Redactor.redact("connect to 192.168.1.20:3000 timed out"))
    }

    @Test
    fun authorizationHeaders_areReplaced() {
        assertEquals("Authorization: <redacted>", Redactor.redact("Authorization: Bearer YWJjZGVm"))
        assertEquals("bearer <redacted> sent", Redactor.redact("bearer abcdef123 sent"))
    }

    @Test
    fun keyValueSecrets_areReplaced() {
        val out = Redactor.redact("api_key=s3cr3t&page=2 token: t0k password=pw")
        assertEquals("api_key=<redacted>&page=2 token: <redacted> password=<redacted>", out)
    }

    @Test
    fun ordinaryText_isUntouched() {
        val text = "Download failed: NO_SPACE after 3 attempts (basic check)"
        assertEquals(text, Redactor.redact(text))
    }

    // ---- Hosts without a scheme (audit 2026-10-06 C06 / SEC-02) ----

    @Test
    fun okHttpUnknownHost_quotedHostIsReplaced() {
        val out = Redactor.redact(
            "java.net.UnknownHostException: Unable to resolve host \"lrr.example.com\": " +
                "No address associated with hostname"
        )
        assertEquals(
            "java.net.UnknownHostException: Unable to resolve host \"<host>\": No address associated with hostname",
            out,
        )
    }

    @Test
    fun jvmUnknownHost_bareHostIsReplaced() {
        assertEquals(
            "java.net.UnknownHostException: <host>: Name or service not known",
            Redactor.redact("java.net.UnknownHostException: nas: Name or service not known"),
        )
        assertEquals(
            "java.net.UnknownHostException: <host>",
            Redactor.redact("java.net.UnknownHostException: lrr.example.com"),
        )
    }

    @Test
    fun androidConnectFailure_hostSlashIpIsReplaced() {
        val out = Redactor.redact(
            "java.net.ConnectException: failed to connect to lrr.example.com/203.0.113.5 (port 3000) " +
                "from /10.0.2.16 (port 39512) after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)"
        )
        assertEquals(
            "java.net.ConnectException: failed to connect to <host>/<ip> (port 3000) " +
                "from /<ip> (port 39512) after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)",
            out,
        )
    }

    @Test
    fun okHttpConnectFailure_singleLabelHostAndPortAreReplaced() {
        assertEquals(
            "java.net.ConnectException: Failed to connect to <host>/<ip>",
            Redactor.redact("java.net.ConnectException: Failed to connect to nas/192.168.1.5:3000"),
        )
        assertEquals(
            "java.net.SocketTimeoutException: failed to connect to <host>/<unresolved>:3000",
            Redactor.redact("java.net.SocketTimeoutException: failed to connect to nas.lan/<unresolved>:3000"),
        )
    }

    @Test
    fun ipv6Literals_areReplaced() {
        assertEquals(
            "failed to connect to <host>/<ip> (port 3000)",
            Redactor.redact("failed to connect to lrr.example.com/2001:db8:85a3::8a2e:370:7334 (port 3000)"),
        )
        assertEquals("connect to [<ip>]:3000 refused", Redactor.redact("connect to [fd12:3456::1]:3000 refused"))
        assertEquals("from <ip> ok", Redactor.redact("from 2001:0db8:0000:0000:0000:ff00:0042:8329 ok"))
        assertEquals("peer <ip>", Redactor.redact("peer ::1"))
        assertEquals("http://<host>/api/info", Redactor.redact("http://[2001:db8::1]:3000/api/info"))
    }

    @Test
    fun hostAndPort_withoutScheme_areReplaced() {
        assertEquals("Invalid server URL: <host>", Redactor.redact("Invalid server URL: nas:3000/lrr"))
        assertEquals("proxy <host> unreachable", Redactor.redact("proxy lrr.example.com:8443 unreachable"))
        assertEquals("login as <host> failed", Redactor.redact("login as admin@nas.local:3000 failed"))
        // Source-file positions are not hosts.
        assertEquals("see Foo.kt:12 and Bar.java:40", Redactor.redact("see Foo.kt:12 and Bar.java:40"))
    }

    @Test
    fun tlsHostnameFailure_hostAndCertificateAreReplaced() {
        val out = Redactor.redact(
            "javax.net.ssl.SSLPeerUnverifiedException: Hostname lrr.example.com not verified:\n" +
                "    certificate: sha256/AbCdEf0123456789+/=\n" +
                "    DN: CN=lrr.example.com,O=My Home\n" +
                "    subjectAltNames: [lrr.example.com, www.lrr.example.com]"
        )
        assertEquals(
            "javax.net.ssl.SSLPeerUnverifiedException: Hostname <host> not verified:\n" +
                "    certificate: <redacted>\n" +
                "    DN: <redacted>\n" +
                "    subjectAltNames: [<host>]",
            out,
        )
    }

    @Test
    fun cleartextNotPermitted_hostIsReplaced() {
        assertEquals(
            "java.net.UnknownServiceException: CLEARTEXT communication to <host> not permitted by network security policy",
            Redactor.redact(
                "java.net.UnknownServiceException: CLEARTEXT communication to nas not permitted by network security policy"
            ),
        )
    }

    @Test
    fun stackFrames_andClassNames_surviveUnchanged() {
        val trace = listOf(
            "java.lang.IllegalStateException: Page 3 out of bounds (size=2) for abc123",
            "\tat com.lanraragi.reader.gallery.TankMemberSource.load(TankMemberSource.kt:193)",
            "\tat com.lanraragi.reader.Foo\$bar\$1.invokeSuspend(Foo.kt:12)",
            "\tat kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)",
            "\tat java.base/java.lang.Thread.run(Thread.java:833)",
            "\tat libcore.io.IoBridge.connect(IoBridge.java:142)",
            "\tat okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:298)",
            "\tat android.os.Handler.dispatchMessage(Handler.java:106)",
            "\tat com.android.internal.os.ZygoteInit.main(Native Method)",
            "Caused by: java.lang.ClassCastException: java.lang.String cannot be cast to java.lang.Integer",
            "\t... 12 more",
            "10-06 12:00:00.123  1234  5678 E AndroidRuntime: \tat com.lanraragi.reader.Crash.save(Crash.kt:118)",
            "  native: #00 pc 000000000004b8a0  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+32)",
            "  | sysTid=1234 nice=-10 cgrp=top-app sched=0/0 handle=0x7b2c4e4f58",
            "Thread=DefaultDispatcher-worker-3",
            "Signature=AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01",
            "FINGERPRINT=google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.036/10956429:userdebug/dev-keys",
            "TopScene=com.lanraragi.reader.ui.scene.GalleryListScene",
            "MEMORY=12.5 MB",
            "VersionName=1.27.1",
            "Reference to Foo::bar failed at 12:34:56.789",
        ).joinToString("\n")
        assertEquals(trace, Redactor.redact(trace))
    }

    @Test
    fun redact_isIdempotent() {
        val once = Redactor.redact(
            "failed to connect to lrr.example.com/[2001:db8::1]:3000 via http://10.0.0.2:3000/api " +
                "Unable to resolve host \"nas\""
        )
        assertFalse(once, once.contains("example") || once.contains("db8") || once.contains("10.0.0.2"))
        assertFalse(once, once.contains("\"nas\""))
        assertEquals(once, Redactor.redact(once))
    }

    @Test
    fun describeServerUrl_keepsOnlySchemeAndLan() {
        assertEquals("scheme=http, lan=true", Redactor.describeServerUrl("http://192.168.1.20:3000"))
        assertEquals("scheme=https, lan=false", Redactor.describeServerUrl("https://lrr.example.com/"))
        assertEquals("scheme=http, lan=true", Redactor.describeServerUrl("http://nas.local:3000"))
        assertEquals("none", Redactor.describeServerUrl(null))
    }

    @Test
    fun isLanHost_coversPrivateRanges() {
        assertTrue(Redactor.isLanHost("10.1.2.3"))
        assertTrue(Redactor.isLanHost("172.16.0.1"))
        assertTrue(Redactor.isLanHost("100.64.0.1"))
        assertTrue(Redactor.isLanHost("[fd00::1]"))
        assertFalse(Redactor.isLanHost("172.32.0.1"))
        assertFalse(Redactor.isLanHost("8.8.8.8"))
    }
}
