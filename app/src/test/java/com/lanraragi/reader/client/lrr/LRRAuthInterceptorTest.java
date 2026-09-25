package com.lanraragi.reader.client.lrr;

import com.lanraragi.reader.MockWebServerAwaitKt;
import com.lanraragi.reader.client.api.*;
import com.lanraragi.reader.client.api.data.*;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Base64;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link LRRAuthInterceptor} — verifies Bearer token injection
 * only for requests targeting the configured LANraragi server.
 *
 * Uses Robolectric for Context, but bypasses EncryptedSharedPreferences
 * by injecting a plain SharedPreferences into LRRAuthManager via reflection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class LRRAuthInterceptorTest {

    private MockWebServer server;
    private OkHttpClient client;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        baseUrl = server.url("").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        client = new OkHttpClient.Builder()
                .addInterceptor(new LRRAuthInterceptor())
                .build();

        // Inject plain SharedPreferences into LRRAuthManager via reflection
        // (avoids EncryptedSharedPreferences which requires Android KeyStore)
        Context ctx = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences("test_lrr_auth", Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        // LRRAuthManager is a Kotlin object — set the field on the singleton INSTANCE
        Field sPrefsField = LRRAuthManager.class.getDeclaredField("sPrefs");
        sPrefsField.setAccessible(true);
        sPrefsField.set(LRRAuthManager.INSTANCE, prefs);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
        LRRAuthManager.clear();
    }

    @Test
    public void noApiKey_noHeader() throws Exception {
        // API key is null by default after clearing prefs
        LRRAuthManager.setServerUrl(baseUrl);

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = MockWebServerAwaitKt.awaitRequest(server, 10);
        assertNull(recorded.getHeader("Authorization"));
    }

    @Test
    public void emptyApiKey_noHeader() throws Exception {
        LRRAuthManager.setApiKey("");
        LRRAuthManager.setServerUrl(baseUrl);

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = MockWebServerAwaitKt.awaitRequest(server, 10);
        assertNull(recorded.getHeader("Authorization"));
    }

    @Test
    public void withApiKey_addsBearer() throws Exception {
        LRRAuthManager.setApiKey("test-key");
        LRRAuthManager.setServerUrl(baseUrl);

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = MockWebServerAwaitKt.awaitRequest(server, 10);
        String authHeader = recorded.getHeader("Authorization");
        assertNotNull("Authorization header should be present", authHeader);
        assertTrue(authHeader.startsWith("Bearer "));
        String token = authHeader.substring("Bearer ".length());
        byte[] decoded = Base64.getDecoder().decode(token);
        assertEquals("test-key", new String(decoded, "UTF-8"));
    }

    @Test
    public void requestToOtherHost_noHeader() throws Exception {
        LRRAuthManager.setApiKey("secret");
        LRRAuthManager.setServerUrl("https://different-host.example.com");

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = MockWebServerAwaitKt.awaitRequest(server, 10);
        assertNull(recorded.getHeader("Authorization"));
    }

    @Test
    public void noServerUrl_noHeader() throws Exception {
        LRRAuthManager.setApiKey("secret");
        // Server URL not set — getServerUrl() returns null

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = MockWebServerAwaitKt.awaitRequest(server, 10);
        assertNull(recorded.getHeader("Authorization"));
    }

    // ── W0-2 hardening tests ──────────────────────────────────────────────────

    /**
     * Same host:port but configured https / request http → credential downgrade,
     * must throw LRRPlaintextRefusedException before the request leaves the device.
     */
    @Test
    public void httpsConfigured_httpRequest_throwsPlaintextRefused() throws Exception {
        LRRAuthManager.setApiKey("secret");
        // Configure HTTPS for the same host:port that MockWebServer listens on (HTTP).
        HttpUrl mockUrl = server.url("/");
        LRRAuthManager.setServerUrl("https://" + mockUrl.host() + ":" + mockUrl.port());

        Request request = new Request.Builder().url(server.url("/api/info")).build();
        try {
            client.newCall(request).execute().close();
            fail("Expected LRRPlaintextRefusedException");
        } catch (LRRPlaintextRefusedException expected) {
            // success
        } catch (IOException unexpected) {
            fail("Expected LRRPlaintextRefusedException, got " + unexpected);
        }
        assertEquals("Request must not reach the server", 0, server.getRequestCount());
    }

    /**
     * Inverse: configured http but request https → also a scheme mismatch → reject.
     * Constructed via HttpUrl directly because MockWebServer is HTTP-only.
     */
    @Test
    public void httpConfigured_httpsRequest_throwsPlaintextRefused() throws Exception {
        LRRAuthManager.setApiKey("secret");
        LRRAuthManager.setServerUrl("http://example.local:3000");

        Request request = new Request.Builder()
                .url("https://example.local:3000/api/info")
                .build();
        try {
            client.newCall(request).execute().close();
            fail("Expected LRRPlaintextRefusedException");
        } catch (LRRPlaintextRefusedException expected) {
            // success
        } catch (IOException unexpected) {
            fail("Expected LRRPlaintextRefusedException, got " + unexpected);
        }
    }

    /**
     * Stored server URL containing userInfo (`user:pass@host`) is treated as
     * malformed/malicious — every request must abort.
     */
    @Test
    public void serverUrlWithUserInfo_throwsPlaintextRefused() throws Exception {
        LRRAuthManager.setApiKey("secret");
        HttpUrl mockUrl = server.url("/");
        LRRAuthManager.setServerUrl(
                "http://attacker:pwd@" + mockUrl.host() + ":" + mockUrl.port());

        Request request = new Request.Builder().url(server.url("/api/info")).build();
        try {
            client.newCall(request).execute().close();
            fail("Expected LRRPlaintextRefusedException");
        } catch (LRRPlaintextRefusedException expected) {
            // success
        } catch (IOException unexpected) {
            fail("Expected LRRPlaintextRefusedException, got " + unexpected);
        }
        assertEquals(0, server.getRequestCount());
    }

    /**
     * Audit verification step 5: a malicious baseUrl
     * `http://attacker.com#@configured.local/api/foo` parses to host=attacker.com
     * with the rest in the fragment. Since the configured URL contains a fragment,
     * the interceptor must reject every request rather than silently leaking the
     * token to either host.
     */
    @Test
    public void serverUrlWithFragment_throwsPlaintextRefused() throws Exception {
        LRRAuthManager.setApiKey("secret");
        HttpUrl mockUrl = server.url("/");
        LRRAuthManager.setServerUrl(
                "http://" + mockUrl.host() + ":" + mockUrl.port() + "/#@evil.local/api");

        Request request = new Request.Builder().url(server.url("/api/info")).build();
        try {
            client.newCall(request).execute().close();
            fail("Expected LRRPlaintextRefusedException");
        } catch (LRRPlaintextRefusedException expected) {
            // success
        } catch (IOException unexpected) {
            fail("Expected LRRPlaintextRefusedException, got " + unexpected);
        }
        assertEquals(0, server.getRequestCount());
    }

    /**
     * Defense in depth: even if app code somehow constructs a request with
     * userInfo in the URL targeting our server, it must be rejected.
     */
    @Test
    public void requestUrlWithUserInfo_throwsPlaintextRefused() throws Exception {
        LRRAuthManager.setApiKey("secret");
        HttpUrl mockUrl = server.url("/");
        LRRAuthManager.setServerUrl("http://" + mockUrl.host() + ":" + mockUrl.port());

        // Build a request URL with userInfo via HttpUrl.Builder so OkHttp accepts it.
        HttpUrl evilUrl = mockUrl.newBuilder()
                .username("alice")
                .password("hunter2")
                .addPathSegment("api")
                .addPathSegment("info")
                .build();
        Request request = new Request.Builder().url(evilUrl).build();
        try {
            client.newCall(request).execute().close();
            fail("Expected LRRPlaintextRefusedException");
        } catch (LRRPlaintextRefusedException expected) {
            // success
        } catch (IOException unexpected) {
            fail("Expected LRRPlaintextRefusedException, got " + unexpected);
        }
        assertEquals(0, server.getRequestCount());
    }

    /**
     * Configured port differs from request port → MISMATCH → no header injected,
     * request still goes out unauthenticated.
     */
    @Test
    public void differentPort_noHeader() throws Exception {
        LRRAuthManager.setApiKey("k");
        // Configure a port that does not match MockWebServer's.
        HttpUrl mockUrl = server.url("/");
        int otherPort = mockUrl.port() == 9999 ? 9998 : 9999;
        LRRAuthManager.setServerUrl("http://" + mockUrl.host() + ":" + otherPort);

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = MockWebServerAwaitKt.awaitRequest(server, 10);
        assertNull(recorded.getHeader("Authorization"));
    }

    /**
     * Host comparison must be case-insensitive (HttpUrl normalizes to lowercase).
     */
    @Test
    public void hostCaseInsensitive_match() throws Exception {
        LRRAuthManager.setApiKey("k");
        HttpUrl mockUrl = server.url("/");
        // Mixed-case version of the host (HttpUrl will lowercase on parse).
        String mixedHost = mockUrl.host().toUpperCase();
        LRRAuthManager.setServerUrl("http://" + mixedHost + ":" + mockUrl.port());

        server.enqueue(new MockResponse().setBody("ok"));
        Request request = new Request.Builder().url(server.url("/api/info")).build();
        client.newCall(request).execute().close();

        RecordedRequest recorded = MockWebServerAwaitKt.awaitRequest(server, 10);
        assertNotNull("Case-insensitive host match expected",
                recorded.getHeader("Authorization"));
    }

    /**
     * A completely unparseable server URL must abort, never silently swallow the
     * token. This is a defense against corrupted prefs.
     */
    @Test
    public void invalidServerUrl_throwsPlaintextRefused() throws Exception {
        LRRAuthManager.setApiKey("k");
        LRRAuthManager.setServerUrl("not-a-valid-url");

        Request request = new Request.Builder().url(server.url("/api/info")).build();
        try {
            client.newCall(request).execute().close();
            fail("Expected LRRPlaintextRefusedException");
        } catch (LRRPlaintextRefusedException expected) {
            // success
        } catch (IOException unexpected) {
            fail("Expected LRRPlaintextRefusedException, got " + unexpected);
        }
        assertEquals(0, server.getRequestCount());
    }
}
